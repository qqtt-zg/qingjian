//! 将限定的本机工具注册给 Koog；参数、意图、配额和操作回执仍由本机执行器管理。
package org.qingjian.speech.learning.ai.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.qingjian.speech.learning.ai.ToolCall

class LearningTool(definition: JSONObject, private val executeLocal: (ToolCall, String, Long) -> JSONObject, private val topic: String,
    private val question: Long, private val guard: () -> Unit, private val progress: (String) -> Unit) :
    Tool<JsonObject, JsonObject>(typeToken<JsonObject>(), typeToken<JsonObject>(), descriptor(definition)) {

    override suspend fun execute(args: JsonObject): JsonObject {
        guard(); progress(when(name) {
            "search_vocabulary" -> "正在查找相关词汇…"
            "get_learning_overview" -> "正在读取学习记录…"
            "prepare_word_card" -> "正在整理词卡并加入待学习…"
            "prepare_word_materials" -> "正在整理释义、例句、搭配与用法…"
            "prepare_practice" -> "正在准备练习题…"
            "get_study_context" -> "正在读取真实学习记录…"
            "prepare_study_plan" -> "正在安排今天的学习…"
            "prepare_study_adjustment" -> "正在根据作答调整后续小测…"
            "prepare_study_reflection" -> "正在整理本轮回顾…"
            else -> "正在准备朗读请求…"
        })
        val result = executeLocal(ToolCall("koog-local", name, args.toString()), topic, question)
        guard()
        return Json.parseToJsonElement(result.toString()).jsonObject
    }

    companion object {
        private fun descriptor(value: JSONObject): ToolDescriptor {
            val function = value.getJSONObject("function")
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")
            val required = parameters.getJSONArray("required").let { keys -> (0 until keys.length()).map { keys.getString(it) }.toSet() }
            val fields = properties.keys().asSequence().map { key ->
                val field = properties.getJSONObject(key)
                ToolParameterDescriptor(key, field.optString("description"), parameter(field))
            }.toList()
            return ToolDescriptor(function.getString("name"), function.getString("description"),
                fields.filter { it.name in required }, fields.filterNot { it.name in required })
        }

        private fun parameter(value: JSONObject): ToolParameterType = when(value.getString("type")) {
            "object" -> {
                val fields = value.getJSONObject("properties")
                val required = value.getJSONArray("required")
                ToolParameterType.Object(fields.keys().asSequence().map { key -> ToolParameterDescriptor(key, fields.getJSONObject(key).optString("description"), parameter(fields.getJSONObject(key))) }.toList(),
                    (0 until required.length()).map { required.getString(it) }, additionalProperties = false)
            }
            "integer" -> ToolParameterType.Integer
            "array" -> ToolParameterType.List(parameter(value.getJSONObject("items")))
            "string" -> if(value.has("enum")) ToolParameterType.Enum(value.getJSONArray("enum").let { values ->
                Array(values.length()) { values.getString(it) }
            }) else ToolParameterType.String
            else -> error("不支持的学习工具参数类型")
        }
    }
}
