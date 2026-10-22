//! 静态译词表的下载与原子安装：断点不留半截文件，哈希不符不替换已装好的表。
//!
//! 判定规则在 GlossaryInstall.kt（可被 JVM 单元测试覆盖），这里只做网络与文件。
//! 表从产品的 `data-vN` Release 取，走公开地址、免鉴权；地址与哈希由调用方给出。
package org.qingjian.speech.learning.glossary

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GlossaryDownload(private val context: Context, private val cancelled: () -> Boolean, private val progress: (String) -> Unit) {
    @Volatile private var connection: HttpURLConnection? = null

    fun close() {
        connection?.disconnect()
    }

    /** 本地已装好的表；没装过返回 null。 */
    fun installed(asset: GlossaryAsset): File? =
        File(root(), asset.name).takeIf { it.isFile }

    /**
     * 下载并安装一个资产，返回装好的文件。
     *
     * 已经装过且哈希一致时直接返回，不重下；下载先写 `.download` 临时文件，校验通过才
     * 原子改名——中途失败或哈希不符时，原来能用的表不受影响。
     */
    fun install(asset: GlossaryAsset, url: String): File {
        val directory = root()
        val target = File(directory, asset.name)
        val staging = File(directory, GlossaryInstall.stagingName(asset.name))
        if (installed(asset) != null && sha256Of(target) == asset.sha256) return target
        staging.delete()
        try {
            val http = URL(url).openConnection() as HttpURLConnection
            connection = http
            try {
                http.instanceFollowRedirects = true
                http.connectTimeout = 10000
                http.readTimeout = 60000
                http.setRequestProperty("Accept", "application/octet-stream")
                check(http.responseCode == 200) { "释义表下载失败（HTTP ${http.responseCode}）" }
                val expected = http.contentLengthLong
                check(expected in 1..MAX_BYTES) { "释义表大小异常" }
                check(context.filesDir.usableSpace > expected + 8L * 1024 * 1024) { "存储空间不足" }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var shown = -1
                http.inputStream.use { input ->
                    staging.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            check(!cancelled()) { "已取消下载" }
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            check(downloaded <= expected) { "释义表大小超出声明" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            val percent = (downloaded * 100 / expected).toInt()
                            if (percent / 10 != shown) {
                                shown = percent / 10
                                progress("正在下载词汇表 $percent%")
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(downloaded == expected && actual == asset.sha256) { "词汇表完整性校验失败" }
            } finally {
                http.disconnect()
                connection = null
            }
            // 校验都过了才替换；renameTo 在同一目录内是原子的。
            if (target.exists()) target.delete()
            check(staging.renameTo(target)) { "词汇表安装失败" }
            return target
        } finally {
            staging.delete()
        }
    }

    private fun root(): File = GlossaryFiles.directory(context)

    private fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (error: Exception) {
        null
    }

    companion object {
        /** 单张表的上限：真实 glossary-en.qj 约 15MB，留足余量但不让异常资产占满磁盘。 */
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}
