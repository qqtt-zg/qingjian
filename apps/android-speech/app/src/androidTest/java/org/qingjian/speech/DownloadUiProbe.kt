//! 真实更新页面的暂停/续传验证；真实签名 APK 与受控分段响应，不连接用户手机。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.qingjian.speech.update.UpdateActivity
import org.qingjian.speech.update.UpdateClient

object DownloadUiProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val candidate = File(app.getExternalFilesDir(null), "candidate.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        candidate.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer, 0, n) } }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val data = JSONObject().put("package", app.packageName).put("version_code", UpdateClient.code(UpdateClient.installed(app)) + 1)
            .put("version_name", "probe").put("size", candidate.length()).put("sha256", sha)
        val gate = CountDownLatch(1)
        var hold = true
        val starts = Collections.synchronizedList(mutableListOf<Long>())
        URL.setURLStreamHandlerFactory { protocol ->
            if(protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection = object : HttpURLConnection(url) {
                    fun bounds(): Pair<Long, Long> {
                        val match = Regex("bytes=(\\d+)-(\\d+)").matchEntire(getRequestProperty("Range"))!!
                        return match.groupValues[1].toLong() to match.groupValues[2].toLong()
                    }
                    override fun connect() = Unit
                    override fun disconnect() {
                        // 查询版本完成不能放行下载；只有断开安装包请求才解除慢速响应。
                        if (url.path != "/api/update") gate.countDown()
                    }
                    override fun usingProxy() = false
                    override fun getResponseCode(): Int {
                        check(getRequestProperty("Authorization") == "Bearer " + "a".repeat(32))
                        check(!instanceFollowRedirects)
                        return if(url.path == "/api/update") 200 else 206
                    }
                    override fun getContentLengthLong(): Long { val (first, last) = bounds(); return last - first + 1 }
                    override fun getHeaderField(name: String): String? {
                        val (first, last) = bounds()
                        return if(name.equals("Content-Range", true)) "bytes $first-$last/${candidate.length()}" else null
                    }
                    override fun getInputStream(): java.io.InputStream {
                        if(url.path == "/api/update") return ByteArrayInputStream(JSONObject().put("update", data).toString().toByteArray())
                        val (first, last) = bounds(); starts += first
                        val input = candidate.inputStream(); input.channel.position(first)
                        val blocked = hold && first == 0L
                        return object : FilterInputStream(input) {
                            var left = last - first + 1; var read = 0L
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                if(blocked && read >= 65536) check(gate.await(15, TimeUnit.SECONDS))
                                if(left == 0L) return -1
                                val n = super.read(buffer, offset, minOf(length.toLong(), left).toInt())
                                if(n > 0) { left -= n; read += n }; return n
                            }
                        }
                    }
                }
            }
        }
        val prefs = app.getSharedPreferences("app_update", 0)
        prefs.edit().clear().commit()
        val folder = File(app.cacheDir, "updates").apply { mkdirs() }
        val partial = File(folder, "$sha.apk.part"); partial.delete()
        File(folder, "$sha.apk").delete()
        app.getSharedPreferences("speech", 0).edit().putString("url", "https://download-ui.invalid/#" + "a".repeat(32)).commit()
        val ui = ProbeUi(test)
        UpdateClient.check(app)
        ui.await("metadata") { !UpdateClient.busy.get() }
        val page = test.startActivitySync(Intent(app, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("update page") { page.hasWindowFocus() }
        ui.click(page, "下载更新")
        ui.await("retained bytes") { partial.length() >= 65536 }
        ui.await("pause available") { ui.button(page.window.decorView, "暂停下载")?.visibility == android.view.View.VISIBLE }
        ui.main { check(prefs.getString("status", "")!!.contains("KB/s")) }
        ui.click(page, "暂停下载")
        ui.await("paused") { !UpdateClient.busy.get() && UpdateClient.resumable(app) }
        val offset = partial.length(); check(offset > 0 && offset < candidate.length())
        ui.await("continue action") { ui.button(page.window.decorView, "继续下载") != null }
        hold = false; starts.clear()
        ui.click(page, "继续下载")
        ui.await("resumed verified package", 90000) { !UpdateClient.busy.get() && UpdateClient.ready(app) }
        check(starts.first() == offset && starts.size > 1)
        ui.await("install action") { ui.button(page.window.decorView, "安装更新") != null }
        ui.main { page.finish() }
        return "PASS update UI: actual signed APK, byte/speed feedback, pause retains partial, continue starts at exact offset, range chunks, final version/signature/hash verification and install action"
    }
}
