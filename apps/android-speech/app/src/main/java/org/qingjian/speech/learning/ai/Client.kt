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
    var task = "AI请求"
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; connection?.disconnect() }

    fun complete(profile: Profile, messages: JSONArray, progress: (String) -> Unit = {}): String {
        val result = turn(profile, messages, null, true, progress)
        check(result.calls.isEmpty() && result.text.isNotBlank()) { "当前文字请求不接受工具调用" }
        return result.text
    }

    fun turn(profile: Profile, messages: JSONArray, tools: JSONArray? = null, allowCalls: Boolean = true, progress: (String) -> Unit = {}): ModelReply {
        profile.validate()
        check(!cancelled) { "请求已取消" }
        progress("正在连接 AI 服务…")
        val data = JSONObject().put("model", profile.model.trim()).put("messages", messages).put("stream", false).put("max_tokens", 1536)
        if(tools != null) data.put("tools", tools).put("tool_choice", if(allowCalls) "auto" else "none")
        val body = data.toString().toByteArray(Charsets.UTF_8)
        val request = open(URL(profile.endpoint()))
        val started=System.nanoTime()
        var succeeded=false
        val metrics=JSONObject().put("model",profile.model).put("input",JSONObject.NULL).put("output",JSONObject.NULL).put("cached",JSONObject.NULL).put("reasoning",JSONObject.NULL)
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
                400 -> "请求格式或工具协议不兼容（HTTP 400）；可在 AI 设置关闭学习工具后重试"
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
            val raw=bytes.toString("UTF-8")
            val usage=JSONObject(raw).optJSONObject("usage")
            fun number(source:JSONObject?,key:String):Any=source?.opt(key).let {if(it is Number&&it.toLong()>=0)it.toLong() else JSONObject.NULL}
            metrics.put("input",number(usage,"prompt_tokens")).put("output",number(usage,"completion_tokens"))
                .put("cached",number(usage?.optJSONObject("prompt_tokens_details"),"cached_tokens"))
                .put("reasoning",number(usage?.optJSONObject("completion_tokens_details"),"reasoning_tokens"))
            val result=ModelReply.parse(raw);succeeded=true;return result
        } finally {
            metrics.put("state",if(succeeded)"成功" else if(cancelled)"取消" else "失败").put("duration_ms",(System.nanoTime()-started)/1_000_000)
            org.qingjian.speech.learning.archive.Usage.capture(task,metrics)
            request.disconnect(); connection = null
        }
    }

    companion object {
        fun parse(body: String): String {
            val result = ModelReply.parse(body)
            check(result.text.isNotBlank() && result.calls.isEmpty()) { "接口没有返回文字回答，请确认所选模型支持文本对话" }
            return result.text
        }

        fun errorMessage(error: Exception): String = when(error) {
            is CancellationException -> "请求已取消；问题保留，可手动重试"
            is SocketTimeoutException -> "等待回答超时；服务端可能仍在处理，请稍后手动重试"
            is IllegalArgumentException, is IllegalStateException -> error.message ?: "配置或回答无效"
            else -> "连接失败或返回格式不兼容，请检查手机网络、接口地址与协议"
        }
    }
}
