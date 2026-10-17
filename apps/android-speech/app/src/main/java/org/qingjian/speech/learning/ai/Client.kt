//! 有界的 Chat Completions 请求；拒绝携带凭证跳转，不记录请求体或服务商错误正文。
package org.qingjian.speech.learning.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

class Client(private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; connection?.disconnect() }

    fun complete(profile: Profile, messages: JSONArray, progress: (String) -> Unit = {}): String {
        profile.validate()
        check(!cancelled) { "请求已取消" }
        progress("正在连接 AI 服务…")
        val body = JSONObject().put("model", profile.model.trim()).put("messages", messages)
            .put("stream", false).put("max_tokens", 1536).toString().toByteArray(Charsets.UTF_8)
        val request = open(URL(profile.endpoint()))
        connection = request
        try {
            if (cancelled) throw CancellationException()
            request.instanceFollowRedirects = false
            request.connectTimeout = 15000
            request.readTimeout = 60000
            request.requestMethod = "POST"
            request.doOutput = true
            request.setRequestProperty("Authorization", "Bearer ${profile.key}")
            request.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            request.setRequestProperty("Accept", "application/json")
            request.setFixedLengthStreamingMode(body.size)
            request.outputStream.use { it.write(body) }
            progress("请求已发送，等待模型回答…")
            val status = request.responseCode
            if (status !in 200..299) throw IllegalStateException(when(status) {
                in 300..399 -> "接口发生跳转，请填写最终 HTTPS 地址（未转发密钥）"
                401, 403 -> "认证失败（HTTP $status），请检查密钥与模型权限"
                404 -> "接口或模型不存在（HTTP 404），请检查 API 地址和模型 ID"
                429 -> "请求受限或额度不足（HTTP 429），请稍后手动重试"
                else -> "服务返回 HTTP $status，请检查模型是否支持 Chat Completions 与 max_tokens 参数"
            })
            progress("正在接收并检查回答…")
            val deadline = System.nanoTime() + 90_000_000_000L
            val bytes = ByteArrayOutputStream()
            request.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while(true) {
                    if(cancelled) throw CancellationException()
                    if(System.nanoTime() > deadline) throw SocketTimeoutException()
                    val n = input.read(buffer)
                    if(n < 0) break
                    check(bytes.size()+n <= 524288) { "回答数据过大，请缩小问题后重试" }
                    bytes.write(buffer, 0, n)
                }
            }
            return parse(bytes.toString("UTF-8"))
        } finally { request.disconnect(); connection = null }
    }

    companion object {
        fun parse(body: String): String {
            val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            val content = choice.getJSONObject("message").opt("content")
            val text = when(content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { index ->
                    content.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("\n")
                else -> ""
            }.trim()
            check(text.isNotBlank()) { "接口没有返回文字回答，请确认所选模型支持文本对话" }
            check(text.length <= 30000) { "回答过长，请缩小问题后重试" }
            return text + if(choice.optString("finish_reason") == "length") "\n\n（回答达到长度上限，可继续追问。）" else ""
        }

        fun errorMessage(error: Exception): String = when(error) {
            is CancellationException -> "请求已取消；问题保留，可手动重试"
            is SocketTimeoutException -> "等待回答超时；服务端可能仍在处理，请稍后手动重试"
            is IllegalArgumentException, is IllegalStateException -> error.message ?: "配置或回答无效"
            else -> "连接失败或返回格式不兼容，请检查手机网络、接口地址与协议"
        }
    }
}
