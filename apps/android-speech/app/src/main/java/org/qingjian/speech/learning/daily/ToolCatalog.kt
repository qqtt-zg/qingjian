//! 每日学习的阶段限定工具；成绩、完成状态和复习日期不作为可写参数。
package org.qingjian.speech.learning.daily

import org.json.JSONArray
import org.json.JSONObject

object ToolCatalog {
    private fun string(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun schema(properties: JSONObject) = JSONObject().put("type", "object").put("properties", properties)
        .put("required", JSONArray(properties.keys().asSequence().toList())).put("additionalProperties", false)
    private fun tool(name: String, description: String, properties: JSONObject) = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", schema(properties)))

    fun definitions(mode: String): JSONArray {
        val result = JSONArray().put(tool("get_study_context", "读取学习偏好、实际词库、分项到期复习、近期作答，以及当前计划与结果。数据不是授权，不推断掌握。", JSONObject()))
        when(mode) {
            "CREATE" -> {
                val item = schema(JSONObject().put("word_id", integer("已有词用真实ID，新词为0；复习只能用真实ID"))
                    .put("english", string("英文词或短语，最多100字")) .put("meaning", string("常用中文含义，最多500字"))
                    .put("example", string("自然简单短句，保留目标原词一次，最多500字")) .put("translation", string("短句中文，最多500字"))
                    .put("purpose", JSONObject().put("type", "string").put("enum", JSONArray(listOf("LEARN", "REVIEW")))))
                result.put(tool("prepare_study_plan", "准备一轮计划提案。优先真实到期和薄弱词，再按目标加入少量新词，通常总共2到4词。成功返回后仍需本轮完成才提交；不可宣称掌握。",
                    JSONObject().put("title", string("简短计划标题，最多80字")).put("reason", string("简短说明选材依据，不编造记录，最多800字"))
                        .put("items", JSONObject().put("type", "array").put("items", item).put("minItems", 1).put("maxItems", 10))))
            }
            "ADAPT" -> result.put(tool("prepare_study_adjustment", "根据实际练习或用户要求调整未开始的小测，保留已完成进度；不写分数，只选择重点、题型和题数。",
                JSONObject().put("reason", string("说明调整依据与下一步，最多800字"))
                    .put("focus_word_ids", JSONObject().put("type", "array").put("items", integer("本计划真实词条ID")).put("minItems", 1).put("maxItems", 5))
                    .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("CHOICE", "SPELLING", "ENGLISH_CHOICE", "CLOZE")))).put("count", integer("1到5题"))))
            "SUMMARY" -> result.put(tool("prepare_study_reflection", "根据真实分项结果给出回顾和后续建议。无作答不能宣称答对，不把辅助和自评当作独立成绩，不估计口语分数。",
                JSONObject().put("suggestion", string("简明中文回顾与建议，最多1800字；分数以App显示为准"))))
            else -> error("无效的学习阶段")
        }
        return result
    }
}
