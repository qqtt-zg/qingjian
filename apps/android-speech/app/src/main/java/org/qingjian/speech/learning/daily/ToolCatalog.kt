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

    fun reviseStudyPlanDefinition(): JSONObject {
        fun string(description: String) = JSONObject().put("type", "string").put("description", description)
        fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)

        val operationSchema = JSONObject().put("type", "object").put("properties", JSONObject().apply {
            put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("drop", "swap", "reorder", "add"))))
            put("word_id", integer("操作目标词条ID (用于 drop, add)"))
            put("from_word_id", integer("被替换的词条ID (用于 swap)"))
            put("to_word_id", integer("替换进入的新词条ID (用于 swap)"))
            put("word_ids", JSONObject().put("type", "array").put("items", integer("词条ID")).put("description", "完整重排后的全部词条ID列表 (用于 reorder)"))
            put("reason", string("操作理由"))
        }).put("required", JSONArray(listOf("type"))).put("additionalProperties", false)

        val params = JSONObject().put("type", "object").put("properties", JSONObject().apply {
            put("explanation", string("简要说明本次调整的教育学理由（最多800字）"))
            put("operations", JSONObject().put("type", "array").put("items", operationSchema).put("minItems", 1).put("maxItems", 10))
        }).put("required", JSONArray(listOf("explanation", "operations"))).put("additionalProperties", false)

        return JSONObject().put("type", "function").put("function", JSONObject().apply {
            put("name", "revise_study_plan")
            put("description", "基于基准计划与上下文提出局部修订操作。仅支持原子操作：drop(word_id)、swap(from_word_id, to_word_id)、reorder(word_ids)、add(word_id)。")
            put("parameters", params)
        })
    }

    fun batchMaterialsDefinition(): JSONObject {
        val pair = JSONObject().put("type", "object").put("properties", JSONObject().apply {
            put("phrase", JSONObject().put("type", "string").put("description", "搭配短语"))
            put("translation", JSONObject().put("type", "string").put("description", "搭配中文"))
        }).put("required", JSONArray(listOf("phrase", "translation"))).put("additionalProperties", false)

        val itemProps = JSONObject().apply {
            put("word_id", JSONObject().put("type", "integer").put("description", "词条真实ID"))
            put("english", JSONObject().put("type", "string").put("description", "英文词汇"))
            put("example", JSONObject().put("type", "string").put("description", "简单例句，最多500字"))
            put("translation", JSONObject().put("type", "string").put("description", "例句中文翻译，最多500字"))
            put("usage", JSONObject().put("type", "string").put("description", "简短用法提示，最多500字"))
            put("collocations", JSONObject().put("type", "array").put("items", pair).put("minItems", 1).put("maxItems", 3))
        }

        val params = JSONObject().put("type", "object").put("properties", JSONObject().apply {
            put("words", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", itemProps).put("required", JSONArray(listOf("word_id", "english", "example", "translation", "collocations", "usage"))).put("additionalProperties", false)).put("minItems", 1).put("maxItems", 3))
        }).put("required", JSONArray(listOf("words"))).put("additionalProperties", false)

        return JSONObject().put("type", "function").put("function", JSONObject().apply {
            put("name", "prepare_word_materials_batch")
            put("description", "批量补充词条学习材料，单次请求上限为 3 词。")
            put("parameters", params)
        })
    }

    fun definitionsForRevision(): JSONArray = JSONArray().put(reviseStudyPlanDefinition())

    fun definitionsForBatchMaterials(): JSONArray = JSONArray().put(batchMaterialsDefinition())
}
