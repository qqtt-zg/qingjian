//! Chat Completions 的文字与工具请求；不把空文字的合法调用误判为连接失败。
package org.qingjian.speech.learning.ai

import org.json.JSONArray
import org.json.JSONObject

data class ModelReply(
    val text: String,

    val calls: List<ToolCall>,

    val message: JSONObject,
) {
    companion object {
        fun parse(body: String): ModelReply {
            val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            val source = choice.getJSONObject("message")
            val content = source.opt("content")
            var text = when(content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { index ->
                    content.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("\n")
                else -> ""
            }.trim()
            check(text.length <= 30000) { "回答过长，请缩小问题后重试" }
            val raw = source.optJSONArray("tool_calls") ?: JSONArray()
            check(raw.length() <= 4) { "一次请求的工具过多，请缩小问题后重试" }
            val calls = (0 until raw.length()).map { index ->
                val value = raw.getJSONObject(index)
                check(value.optString("type") == "function") { "接口返回了不支持的工具类型" }
                val function = value.getJSONObject("function")
                ToolCall(value.getString("id"), function.getString("name"), function.getString("arguments")).also {
                    check(it.id.isNotBlank() && it.id.length <= 200 && it.name.length in 1..80 && it.arguments.length <= 12000) { "工具调用格式无效" }
                }
            }
            check(calls.map { it.id }.distinct().size == calls.size) { "工具调用编号重复" }
            check(text.isNotBlank() || calls.isNotEmpty()) { "接口没有返回文字或工具请求，请检查模型兼容性" }
            if(choice.optString("finish_reason") == "length") {
                check(calls.isEmpty()) { "工具参数被截断，请缩小问题后重试" }
                text += "\n\n（回答达到长度上限，可继续追问。）"
            }
            // 只回传协议需要的字段，不携带服务商未知扩展。
            val message = JSONObject().put("role", "assistant").put("content", if(text.isBlank()) JSONObject.NULL else text)
            if(calls.isNotEmpty()) message.put("tool_calls", JSONArray(calls.map {
                JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject().put("name", it.name).put("arguments", it.arguments))
            }))
            return ModelReply(text, calls, message)
        }
    }
}
