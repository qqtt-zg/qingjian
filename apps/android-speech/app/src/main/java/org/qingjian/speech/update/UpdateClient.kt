//! 复用电脑配对凭证检查和下载更新；只接受同包名、同签名的较新 APK。
package org.qingjian.speech.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import org.qingjian.speech.Pairing
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object UpdateClient {
    private val executor = Executors.newSingleThreadExecutor()

    val busy = AtomicBoolean(false)

    val downloading = AtomicBoolean(false)

    private val paused = AtomicBoolean(false)

    // 公网云端更新源（免连电脑，通过国内加速镜像直连，不走 VPN）
    private val CLOUD_UPDATE_URLS = listOf(
        "https://ghfast.top/https://raw.githubusercontent.com/qqtt-zg/qingjian/integrate/upstream-20261016-113659/apps/android-speech/updates.json",
        "https://raw.gitmirror.com/qqtt-zg/qingjian/integrate/upstream-20261016-113659/apps/android-speech/updates.json"
    )

    @Volatile private var activeConnection: HttpURLConnection? = null

    private fun prefs(context: Context) = context.getSharedPreferences("app_update", 0)

    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags())

    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { hash(it.toByteArray()) }.toSet()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun pair(context: Context): Pairing = Pairing.parse(context.getSharedPreferences("speech", 0).getString("url", "").orEmpty())

    private fun status(context: Context, value: String) { prefs(context).edit().putString("status", value).apply() }

    private fun work(context: Context, action: (Context) -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try { action(app) }
            catch (error: Exception) { status(app, when(error) {
                is UpdateFailure -> error.message.orEmpty()
                is CancellationException -> "下载已暂停，已保留进度，可继续下载"
                is SocketTimeoutException -> "连接等待超时，请确认电脑在线后重试；已下载部分保留"
                is IOException -> "连接或文件读写中断，请检查网络与手机存储后重试；已下载部分保留"
                is IllegalArgumentException -> error.message ?: "更新信息无效，请重新检查"
                else -> "更新未完成，请重新检查更新；已下载部分保留"
            }) }
            finally { activeConnection = null; downloading.set(false); busy.set(false) }
        }
    }

    private fun open(pair: Pairing, path: String): HttpURLConnection {
        val http = URL(pair.base + path).openConnection() as HttpURLConnection
        http.instanceFollowRedirects = false
        http.connectTimeout = 15000
        http.readTimeout = 45000
        http.setRequestProperty("Accept-Encoding", "identity")
        http.setRequestProperty("Authorization", "Bearer ${pair.token}")
        return http
    }

    fun metadata(context: Context): JSONObject? = runCatching {
        JSONObject(prefs(context).getString("metadata", "").orEmpty())
    }.getOrNull()

    fun validateMetadata(context: Context, data: JSONObject) {
        require(data.getString("package") == context.packageName) { "安装包不属于当前应用" }
        require(data.getLong("version_code") > code(installed(context))) { "版本不高于当前版本" }
        require(data.getString("version_name").length in 1..100) { "版本名称无效" }
        require(data.getLong("size") in 1..1024L * 1024 * 1024) { "安装包大小无效" }
        require(data.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "安装包校验信息无效" }
    }

    fun hasUpdate(context: Context): Boolean = runCatching {
        val data = metadata(context) ?: return false
        data.getLong("version_code") > code(installed(context))
    }.getOrDefault(false)

    fun check(context: Context, onResult: ((Boolean) -> Unit)? = null) = work(context) { app ->
        status(app, "正在检查应用更新…")
        var foundData: JSONObject? = null
        var isFromCloud = false

        // 1. 优先尝试从公网云端（GitHub 加速源）获取更新，彻底脱离电脑
        for (cloudUrl in CLOUD_UPDATE_URLS) {
            try {
                val http = URL(cloudUrl).openConnection() as HttpURLConnection
                http.instanceFollowRedirects = true
                http.connectTimeout = 4000
                http.readTimeout = 6000
                if (http.responseCode == 200) {
                    val raw = http.inputStream.use { it.readBytes() }
                    val json = JSONObject(String(raw, Charsets.UTF_8))
                    if (json.getString("package") == app.packageName && json.getLong("version_code") > code(installed(app))) {
                        foundData = json
                        isFromCloud = true
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. 如果云端没有更新或网络不可达，尝试向已配对的电脑中继查询（保底）
        if (foundData == null) {
            runCatching {
                val source = pair(app)
                val http = open(source, "/api/update")
                try {
                    if (http.responseCode == 200) {
                        val bytes = http.inputStream.use { it.readBytes() }
                        val updateObj = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("update")
                        if (updateObj != null && updateObj.getLong("version_code") > code(installed(app))) {
                            foundData = updateObj
                            isFromCloud = false
                        }
                    }
                } finally { http.disconnect() }
            }
        }

        if (foundData == null) {
            prefs(app).edit().remove("metadata").remove("ready").apply()
            status(app, "当前已是最新版本")
            onResult?.invoke(false)
        } else {
            validateMetadata(app, foundData)
            val previous = metadata(app)
            val editor = prefs(app).edit().putString("metadata", foundData.toString())
            if (!isFromCloud) {
                val source = pair(app)
                editor.putString("source", source.base + "/#" + source.token)
            } else {
                editor.remove("source")
            }
            if (previous == null || previous.optString("sha256") != foundData.getString("sha256") ||
                previous.optLong("version_code") != foundData.getLong("version_code")) editor.remove("ready")
            editor.apply()
            status(app, "发现新版本 ${foundData.getString("version_name")}（构建 ${foundData.getLong("version_code")}）")
            onResult?.invoke(true)
        }
    }

    fun download(context: Context, restart: Boolean = false) = work(context) { app ->
        downloading.set(true); paused.set(false)
        val data = checkNotNull(metadata(app))
        validateMetadata(app, data)
        val cloudDownloadUrl = data.optString("download_url", "").trim()
        val isCloudDownload = cloudDownloadUrl.startsWith("http://") || cloudDownloadUrl.startsWith("https://")
        if (!isCloudDownload) {
            val source = Pairing.parse(prefs(app).getString("source", "").orEmpty())
            val known = org.qingjian.speech.learning.ConnectionControls.loadPairings(prefs(app))
            require(source == pair(app) || known.any { it.host == source.host }) { "配对已变化，请重新检查更新" }
        }
        val folder = File(app.cacheDir, "updates").apply { mkdirs() }
        val destination = archiveFile(app, data)
        val partial = File(folder, destination.name + ".part")
        val size = data.getLong("size")
        if(restart && partial.exists()) check(partial.delete())
        if(folder.usableSpace <= (size - partial.length()).coerceAtLeast(0) + 32L * 1024 * 1024) throw UpdateFailure("手机存储空间不足，请先释放空间")
        prefs(app).edit().remove("ready").apply()
        val started = System.nanoTime()
        val before = partial.length()
        PackageDownload({ start, end ->
            val conn = if (isCloudDownload) {
                // 公网云端下载：直接连接 GitHub 加速直链，不发本地鉴权凭证
                val http = URL(cloudDownloadUrl).openConnection() as HttpURLConnection
                http.instanceFollowRedirects = true
                http.connectTimeout = 15000
                http.readTimeout = 45000
                http.setRequestProperty("Accept-Encoding", "identity")
                http
            } else {
                // 电脑中继下载：走原有配对中继接口
                val source = Pairing.parse(prefs(app).getString("source", "").orEmpty())
                open(source, "/api/update/" + data.getString("sha256"))
            }
            conn.apply {
                setRequestProperty("Range", "bytes=$start-$end")
                setRequestProperty("If-Range", "\"${data.getString("sha256")}\"")
                activeConnection = this
            }
        }, { paused.get() }).transfer(partial, size, data.getString("sha256"), { received ->
            val seconds = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.1)
            val speed = ((received - before).coerceAtLeast(0) / 1024 / seconds).toInt()
            status(app, String.format(Locale.ROOT, "正在下载更新 %d%% · %.1f / %.1f MB · %d KB/s", received * 100 / size, received / 1048576.0, size / 1048576.0, speed))
        }, { message -> status(app, message) })
        if(paused.get()) throw CancellationException()
        validateArchive(app, partial, data)
        check(partial.renameTo(destination)) { "安装包保存失败，请检查手机存储空间" }
        prefs(app).edit().putString("ready", data.getString("sha256")).apply()
        status(app, "下载完成：${data.getString("version_name")}（构建 ${data.getLong("version_code")}），点击安装更新")
    }

    fun pauseDownload() { if(downloading.get()) { paused.set(true); activeConnection?.disconnect() } }

    fun resumable(context: Context): Boolean = runCatching {
        val data = metadata(context) ?: return false
        val file = File(context.cacheDir, "updates/" + archiveFile(context, data).name + ".part")
        file.isFile && file.length() in 1..data.getLong("size")
    }.getOrDefault(false)

    private fun archiveFile(context: Context, data: JSONObject): File {
        val digest = data.getString("sha256")
        require(digest.matches(Regex("[a-f0-9]{64}")))
        // 每个包保持独立的文件和 content URI，避免系统安装页复用上一包的标识。
        return File(context.cacheDir, "updates/$digest.apk")
    }

    fun ready(context: Context): Boolean = runCatching {
        val data = metadata(context) ?: return false
        val file = archiveFile(context, data)
        data.optLong("version_code") > code(installed(context)) &&
            prefs(context).getString("ready", "") == data.optString("sha256") &&
            file.isFile && file.length() == data.getLong("size")
    }.getOrDefault(false)

    fun validateArchive(context: Context, file: File, data: JSONObject) {
        validateMetadata(context, data)
        require(file.length() == data.getLong("size"))
        val archive = checkNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags()))
        require(archive.packageName == context.packageName && code(archive) == data.getLong("version_code"))
        val current = certificates(installed(context))
        require(current.isNotEmpty() && certificates(archive) == current) { "安装包签名不一致" }
    }

    fun verifyForInstall(context: Context, callback: (File?) -> Unit) {
        if (busy.get()) return
        work(context) { app ->
            status(app, "正在校验安装包…")
            var result: File? = null
            try {
                val data = checkNotNull(metadata(app))
                val file = archiveFile(app, data)
                validateArchive(app, file, data)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val block = ByteArray(65536)
                    while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == data.getString("sha256"))
                result = file
                status(app, "安装包校验通过：${data.getString("version_name")}（构建 ${data.getLong("version_code")}），请在系统界面确认安装")
            } catch (_: Exception) {
                prefs(app).edit().remove("ready").apply()
                status(app, "缓存安装包与目标版本不一致或已损坏，请重新下载安装包")
            } finally { android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) } }
        }
    }
}
