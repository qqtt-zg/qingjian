//! 受控断网/超时验证字节续传、暂停、旧服务兼容与损坏拒绝，不使用真实网络。
package org.qingjian.speech

import android.app.Instrumentation
import org.qingjian.speech.update.PackageDownload
import org.qingjian.speech.update.UpdateFailure
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

object TransferProbe {
    fun run(test: Instrumentation): String {
        val data = ByteArray(384 * 1024) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val partial = File(test.targetContext.cacheDir, "resume-probe.part")
        partial.delete()
        val requests = mutableListOf<Long>()
        var fail = 1
        var wrongHeader = false
        var legacy = false
        fun open(start: Long, end: Long): HttpURLConnection {
            requests += start
            val begin = if(legacy) 0 else start.toInt()
            val last = if(legacy) data.lastIndex else end.toInt()
            val limit = if(fail > 0) { fail--; 8192 } else Int.MAX_VALUE
            return object : HttpURLConnection(URL("https://resume.invalid/file")) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = if(legacy) 200 else 206
                override fun getContentLengthLong() = (last - begin + 1).toLong()
                override fun getHeaderField(name: String): String? = if(name.equals("Content-Range", true))
                    "bytes ${start + if(wrongHeader) 1 else 0}-$end/${data.size}" else null
                override fun getInputStream(): InputStream {
                    val input = ByteArrayInputStream(data, begin, last - begin + 1)
                    return object : InputStream() {
                        var read = 0
                        override fun read(): Int = error("block read expected")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if(read >= limit) throw SocketTimeoutException()
                            val n = input.read(buffer, offset, minOf(length, limit - read))
                            if(n > 0) read += n
                            return n
                        }
                    }
                }
            }
        }
        val notices = mutableListOf<String>()
        fun transfer(cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}) =
            PackageDownload(::open, cancelled, 65536).transfer(partial, data.size.toLong(), sha, progress) { notices += it }
        transfer()
        check(requests.take(2) == listOf(0L, 8192L))
        check(partial.readBytes().contentEquals(data))
        check(notices.any { it.contains("自动重试") })
        partial.delete(); requests.clear(); fail = 5
        check(runCatching { transfer() }.exceptionOrNull() is UpdateFailure)
        val retained = partial.length()
        check(retained == 24576L && requests.size == 3)
        fail = 0; requests.clear(); transfer()
        check(requests.first() == retained && partial.readBytes().contentEquals(data))
        partial.delete(); requests.clear()
        var paused = false
        check(runCatching { transfer({ paused }) { if(it > 0) paused = true } }.exceptionOrNull() is CancellationException)
        check(partial.length() in 1 until data.size.toLong())
        val pausedAt = partial.length(); requests.clear(); transfer()
        check(requests.first() == pausedAt && partial.readBytes().contentEquals(data))
        partial.writeBytes(data.copyOfRange(0, 4096)); wrongHeader = true
        check(runCatching { transfer() }.exceptionOrNull() is UpdateFailure)
        check(partial.length() == 4096L)
        wrongHeader = false; legacy = true
        check(runCatching { transfer() }.exceptionOrNull()?.message?.contains("不支持断点续传") == true)
        check(partial.length() == 4096L)
        partial.delete(); transfer(); check(partial.readBytes().contentEquals(data))
        legacy = false; partial.writeBytes(ByteArray(4096)); fail = 0
        check(runCatching { transfer() }.exceptionOrNull()?.message?.contains("完整性校验失败") == true)
        check(!partial.exists())
        return "PASS download resume: short response/timeout retry from exact offset, retained progress after repeated failure, pause/resume, invalid Content-Range rejection, legacy full-download fallback, corrupt partial hash rejection"
    }
}
