//! 以固定字节区间下载哈希绑定的安装包；中断保留文件，最终仍验证完整 SHA-256。
package org.qingjian.speech.update

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class PackageDownload(
    private val open: (Long, Long) -> HttpURLConnection,

    private val cancelled: () -> Boolean = { false },

    private val chunkSize: Long = 4L * 1024 * 1024,
) {
    fun transfer(partial: File, size: Long, sha: String, progress: (Long) -> Unit, notice: (String) -> Unit) {
        require(size in 1..1024L * 1024 * 1024 && sha.matches(Regex("[a-f0-9]{64}")) && chunkSize > 0)
        if(partial.length() > size) check(partial.delete())
        var failures = 0; var recoveries = 0; var shown = 0L
        progress(partial.length())
        while(partial.length() < size) {
            stopIfCancelled()
            val start = partial.length()
            val end = minOf(size - 1, start + chunkSize - 1)
            var http: HttpURLConnection? = null
            try {
                http = open(start, end)
                stopIfCancelled()
                val code = http.responseCode
                val responseEnd = when(code) {
                    206 -> {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(http.getHeaderField("Content-Range").orEmpty())
                            ?: throw UpdateFailure("电脑返回的续传信息无效，请重新检查更新")
                        val values = range.groupValues.drop(1).map { it.toLongOrNull() ?: throw UpdateFailure("续传范围无效") }
                        if(values[0] != start || values[1] !in start..end || values[2] != size) throw UpdateFailure("安装包续传范围不匹配，请重新检查更新")
                        values[1]
                    }
                    200 -> {
                        if(start != 0L) throw UpdateFailure("电脑连接工具尚不支持断点续传，请更新电脑端工具；已下载部分保留")
                        size - 1
                    }
                    401, 403 -> throw UpdateFailure("配对已失效，请重新连接或扫码")
                    404 -> throw UpdateFailure("电脑上的该安装包已不可用，请重新检查更新")
                    in 300..399 -> throw UpdateFailure("下载地址发生跳转，已停止转发配对凭证，请重新连接电脑")
                    408, 429, in 500..599 -> throw IOException("temporary HTTP failure")
                    else -> throw UpdateFailure("下载服务返回 HTTP $code，请稍后重试")
                }
                if(http.contentLengthLong != responseEnd - start + 1) throw UpdateFailure("安装包长度与更新信息不一致，请重新检查更新")
                var received = start
                http.inputStream.use { input -> FileOutputStream(partial, start > 0).use { output ->
                    val block = ByteArray(65536)
                    while(received <= responseEnd) {
                        stopIfCancelled()
                        val count = input.read(block, 0, minOf(block.size.toLong(), responseEnd - received + 1).toInt())
                        if(count < 0) throw EOFException()
                        if(count == 0) continue
                        output.write(block, 0, count); received += count
                        val now = System.nanoTime()
                        if(now - shown >= 250_000_000L) { progress(received); shown = now }
                    }
                } }
                progress(received); failures = 0
            } catch(error: IOException) {
                stopIfCancelled()
                if(partial.parentFile!!.usableSpace < 1024 * 1024) throw UpdateFailure("手机存储空间不足，已下载部分保留")
                failures++; recoveries++
                progress(partial.length())
                if(failures >= 3 || recoveries > 8) throw UpdateFailure("下载连接多次中断，已保留下载进度，点击继续下载")
                notice("连接中断，自动重试 $failures / 3；已下载部分保留")
                Thread.sleep(500L * failures)
            } catch(error: Exception) {
                stopIfCancelled()
                throw error
            } finally { http?.disconnect() }
        }
        stopIfCancelled()
        notice("下载完成，正在校验完整安装包…")
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().use { input ->
            val block = ByteArray(65536)
            while(true) { stopIfCancelled(); val n = input.read(block); if(n < 0) break; digest.update(block, 0, n) }
        }
        if(digest.digest().joinToString("") { "%02x".format(it) } != sha) {
            partial.delete()
            throw UpdateFailure("安装包完整性校验失败，已丢弃损坏数据，请重新下载")
        }
    }

    private fun stopIfCancelled() { if(cancelled()) throw CancellationException() }
}
