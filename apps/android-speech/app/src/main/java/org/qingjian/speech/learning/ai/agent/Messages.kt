//! Koog 与现有文字协议之间的严格映射；工具结果保留调用 ID，不携带推理或未知扩展。
package org.qingjian.speech.learning.ai.agent

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.ai.ModelReply

object Messages {
    fun prompt(values: JSONArray): Prompt = Prompt((0 until values.length()).map { index ->
        val value = values.getJSONObject(index)
        val content = value.getString("content")
        when(value.getString("role")) {
            "system" -> Message.System(content, RequestMetaInfo.Empty)
            "user" -> Message.User(content, RequestMetaInfo.Empty)
            "assistant" -> Message.Assistant(content, ResponseMetaInfo.Empty)
            else -> error("不支持的初始对话角色")
        }
    }, "qingjian-learning")

    fun wire(prompt: Prompt): JSONArray = JSONArray().apply {
        for(message in prompt.messages) when(message) {
            is Message.System -> put(JSONObject().put("role", "system").put("content", message.textContent()))
            is Message.User -> {
                check(message.parts.all { it is MessagePart.Text || it is MessagePart.Tool.Result }) { "当前只支持文字和学习工具结果" }
                if(message.parts.any { it is MessagePart.Text }) put(JSONObject().put("role", "user").put("content", message.textContent()))
                for(result in message.parts.filterIsInstance<MessagePart.Tool.Result>()) {
                    check(!result.id.isNullOrBlank()) { "工具结果缺少调用编号" }
                    put(JSONObject().put("role", "tool").put("tool_call_id", result.id).put("content", result.output))
                }
            }
            is Message.Assistant -> {
                check(message.parts.all { it is MessagePart.Text || it is MessagePart.Tool.Call }) { "当前只支持文字和学习工具调用" }
                val calls = message.parts.filterIsInstance<MessagePart.Tool.Call>()
                val text = message.textContent()
                val value = JSONObject().put("role", "assistant").put("content", if(text.isBlank()) JSONObject.NULL else text)
                if(calls.isNotEmpty()) value.put("tool_calls", JSONArray(calls.map {
                    JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject().put("name", it.tool).put("arguments", it.args))
                }))
                put(value)
            }
        }
    }

    fun response(value: ModelReply): Message.Assistant {
        val parts = mutableListOf<MessagePart.ResponsePart>()
        if(value.text.isNotBlank()) parts += MessagePart.Text(value.text)
        parts += value.calls.map { MessagePart.Tool.Call(it.id, it.name, it.arguments) }
        return Message.Assistant(parts, ResponseMetaInfo.Empty)
    }
}
