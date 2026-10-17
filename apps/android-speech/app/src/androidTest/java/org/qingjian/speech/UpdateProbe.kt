//! 隔离模拟器探针：受控 HTTPS 响应、真实签名 APK、下载校验和 FileProvider 读取。
package org.qingjian.speech

import android.app.Instrumentation
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.qingjian.speech.update.UpdateClient
import org.qingjian.speech.update.UpdateActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.MessageDigest

object UpdateProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val candidate = File(app.getExternalFilesDir(null), "candidate.apk")
        check(candidate.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        candidate.inputStream().use { input ->
            val block = ByteArray(65536)
            while (true) { val n = input.read(block); if (n < 0) break; digest.update(block, 0, n) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val current = UpdateClient.code(UpdateClient.installed(app))
        val data = JSONObject().put("package", app.packageName).put("version_code", current + 1)
            .put("version_name", "probe").put("size", candidate.length()).put("sha256", sha)
        var response = JSONObject().put("update", data)
        var responseCode = 200
        URL.setURLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode(): Int {
                        check(getRequestProperty("Authorization") == "Bearer " + "a".repeat(32))
                        check(!instanceFollowRedirects)
                        return responseCode
                    }
                    override fun getContentLengthLong() = candidate.length()
                    override fun getInputStream() = if (url.path == "/api/update")
                        ByteArrayInputStream(response.toString().toByteArray()) else candidate.inputStream()
                }
            }
        }
        app.getSharedPreferences("speech", 0).edit().putString("url", "https://update.invalid/#" + "a".repeat(32)).commit()
        fun waitWork() {
            val deadline = System.nanoTime() + 90_000_000_000L
            while (UpdateClient.busy.get() && System.nanoTime() < deadline) Thread.sleep(20)
            check(!UpdateClient.busy.get()) { "update worker timed out" }
        }
        UpdateClient.check(app); waitWork()
        check(UpdateClient.metadata(app)?.getString("sha256") == sha)
        val prefs = app.getSharedPreferences("app_update", 0)
        val folder = File(app.cacheDir, "updates").apply { mkdirs() }
        File(folder, "$sha.apk").delete()
        // 旧版只看 ready 标记和固定路径存在，会把遗留缓存显示为可安装。
        File(folder, "update.apk").writeText("stale package")
        prefs.edit().putString("ready", sha).commit()
        check(!UpdateClient.ready(app)) { "legacy cache must require a fresh download" }
        // 新版元数据必须对应真实 APK 版本，不能只信电脑提供的版本号。
        UpdateClient.validateArchive(app, candidate, data)
        data.put("sha256", "0".repeat(64))
        UpdateClient.check(app); waitWork(); UpdateClient.download(app); waitWork()
        check(!UpdateClient.ready(app)) { "corrupt download accepted" }
        data.put("sha256", sha)
        UpdateClient.check(app); waitWork(); UpdateClient.download(app); waitWork()
        check(UpdateClient.ready(app)) { app.getSharedPreferences("app_update", 0).getString("status", "") ?: "" }
        val ready = File(app.cacheDir, "updates/$sha.apk")
        val uri = FileProvider.getUriForFile(app, app.packageName + ".updates", ready)
        check(uri.scheme == "content")
        app.contentResolver.openInputStream(uri)!!.use { check(it.read() == 0x50) }
        data.put("sha256", "1".repeat(64))
        UpdateClient.check(app); waitWork()
        check(!UpdateClient.ready(app))
        check(!prefs.contains("ready")) { "new metadata retained old install marker" }
        data.put("sha256", sha)
        UpdateClient.check(app); waitWork()
        prefs.edit().putString("ready", sha).commit()
        // 改变内容但保留大小、包名、版本和签名信息，必须由最终哈希校验拒绝。
        java.io.RandomAccessFile(ready, "rw").use { file ->
            file.seek(file.length() - 1); val original = file.readByte()
            file.seek(file.length() - 1); file.writeByte(original.toInt() xor 1)
        }
        val rejected = java.util.concurrent.CountDownLatch(1)
        var badResult: File? = ready
        UpdateClient.verifyForInstall(app) { file -> badResult = file; rejected.countDown() }
        check(rejected.await(90, java.util.concurrent.TimeUnit.SECONDS)); waitWork()
        check(badResult == null && !UpdateClient.ready(app)) { "invalid cache stayed installable" }
        check(prefs.getString("status", "")!!.contains("重新下载"))
        UpdateClient.download(app); waitWork()
        check(UpdateClient.ready(app))
        val done = java.util.concurrent.CountDownLatch(1)
        var verified: File? = null
        UpdateClient.verifyForInstall(app) { file -> verified = file; done.countDown() }
        check(done.await(90, java.util.concurrent.TimeUnit.SECONDS))
        waitWork()
        check(verified == ready) { "installer selected another cached package" }
        val activity = test.startActivitySync(android.content.Intent(app, UpdateActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        test.waitForIdleSync()
        check(app.packageManager.canRequestPackageInstalls()) { "Enable REQUEST_INSTALL_PACKAGES app-op in this isolated emulator" }
        val installFilter = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
            addDataType("application/vnd.android.package-archive")
            addDataScheme("content")
        }
        val installer = test.addMonitor(installFilter, Instrumentation.ActivityResult(0, null), true)
        fun installButton(view: android.view.View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == "安装更新") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                installButton(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        fun retryButton(view: android.view.View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == "重新下载安装包") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                retryButton(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        val pageDeadline = System.nanoTime() + 5_000_000_000L
        var shown = false
        while(!shown && System.nanoTime() < pageDeadline) {
            test.runOnMainSync { shown = installButton(activity.window.decorView) != null }
            if(!shown) Thread.sleep(30)
        }
        var retried = false
        test.runOnMainSync { retried = retryButton(activity.window.decorView)?.takeIf { it.visibility == android.view.View.VISIBLE }?.performClick() == true }
        check(retried) { "redownload action missing for cached update" }
        waitWork(); check(UpdateClient.ready(app)); Thread.sleep(600); test.waitForIdleSync()
        var clicked = false
        test.runOnMainSync { clicked = installButton(activity.window.decorView)?.performClick() == true }
        check(clicked)
        waitWork(); test.waitForIdleSync()
        check(installer.hits == 1) { "System installer intent was not launched" }
        test.removeMonitor(installer)
        test.runOnMainSync { activity.finish() }
        data.put("version_code", current)
        check(runCatching { UpdateClient.validateMetadata(app, data) }.isFailure)
        data.put("version_code", current + 1).put("package", "other.app")
        check(runCatching { UpdateClient.validateMetadata(app, data) }.isFailure)
        responseCode = 302
        UpdateClient.check(app); waitWork()
        check(app.getSharedPreferences("app_update", 0).getString("status", "")!!.contains("未完成"))
        responseCode = 404
        UpdateClient.check(app); waitWork()
        check(app.getSharedPreferences("app_update", 0).getString("status", "")!!.contains("不支持"))
        responseCode = 200; response = JSONObject().put("update", JSONObject.NULL)
        UpdateClient.check(app); waitWork()
        check(UpdateClient.metadata(app) == null)
        return "update probe passed: legacy cache rejection, metadata invalidation, corrupted cache recovery, hash-specific installer file/URI, signed archive, authenticated download, provider/UI, redirect rejection, old relay"
    }
}
