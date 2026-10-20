//! 五项学习能力及执行边界；查询有界、词卡自动加入，朗读和练习依赖明确需求。
package org.qingjian.speech.learning.ai.tools

import org.json.JSONArray
import org.json.JSONObject

object Catalog {
    fun definitions(): JSONArray {
        val result = JSONArray()
        fun string(description: String) = JSONObject().put("type", "string").put("description", description)
        fun number(description: String) = JSONObject().put("type", "integer").put("description", description)
        fun tool(name: String, description: String, properties: JSONObject, required: List<String>) {
            result.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray(required)).put("additionalProperties", false))))
        }
        tool("search_vocabulary", "查询本机词库的英文、中文笔记及接触计数。空查询返回最近条目，最多十个；听过不等于掌握。",
            JSONObject().put("query", string("英文或中文关键词，最多100字")).put("limit", number("1到10")), listOf("query", "limit"))
        tool("get_learning_overview", "查看实际测验汇总和最多五条当前错题；没有错题不等于已掌握，不编造到期复习。", JSONObject(), emptyList())
        tool("prepare_word_card", "整理词卡并自动加入待学习。保留已有笔记，新增词卡可撤销；不代表掌握。每次只扩展少量相关词。",
            JSONObject().put("english", string("英文词或短语，最多100字")).put("meaning", string("常用中文含义，最多500字"))
                .put("example", string("一句简单英文例句，最多500字")).put("translation", string("例句中文，最多500字")),
            listOf("english", "meaning", "example", "translation"))
        tool("prepare_practice", "用户明确要求练习时，用本机词条直接准备最多五题，App 呈现并评分。优先 ENGLISH_CHOICE；CLOZE 需要词卡中已有包含原词的例句。",
            JSONObject().put("word_ids", JSONObject().put("type", "array").put("items", number("本机词条ID")).put("minItems", 1).put("maxItems", 5))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("CHOICE", "SPELLING", "ENGLISH_CHOICE", "CLOZE")))), listOf("word_ids", "kind"))
        tool("request_pronunciation", "用户明确要求时直接提交已收录词条的朗读请求，使用现有音色，不操作麦克风或修改设置。提交不等于播放完成。",
            JSONObject().put("word_id", number("查询得到的本机词条ID")), listOf("word_id"))
        return result
    }
}
