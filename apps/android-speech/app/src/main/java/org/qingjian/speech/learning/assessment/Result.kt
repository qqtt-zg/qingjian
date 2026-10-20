//! 结构化真实评测结果；服务端总分与各维度独立保留，不推算缺失指标。
package org.qingjian.speech.learning.assessment

import org.json.JSONArray
import org.json.JSONObject

data class Result(val total: Double, val accuracy: Double?, val fluency: Double?, val completeness: Double?,
    val words: List<WordResult>) {
    fun json(): JSONObject=JSONObject().put("total",total).put("accuracy",accuracy ?: JSONObject.NULL)
        .put("fluency",fluency ?: JSONObject.NULL).put("completeness",completeness ?: JSONObject.NULL)
        .put("words",JSONArray(words.map {JSONObject().put("text",it.text).put("score",it.score ?: JSONObject.NULL)
            .put("issue",it.issue).put("phones",JSONArray(it.phones))}))
    companion object {
        fun from(value: JSONObject): Result {
            fun number(name: String):Double?=if(value.isNull(name))null else value.getDouble(name).also {require(it.isFinite() && it in 0.0..100.0)}
            val words=value.getJSONArray("words");require(words.length()<=500)
            return Result(checkNotNull(number("total")),number("accuracy"),number("fluency"),number("completeness"),(0 until words.length()).map {i->
                val item=words.getJSONObject(i);val phones=item.getJSONArray("phones");require(phones.length()<=100)
                val score=if(item.isNull("score"))null else item.getDouble("score").also {require(it.isFinite() && it in 0.0..100.0)}
                WordResult(item.getString("text").take(500),score,item.getString("issue").take(200),(0 until phones.length()).map {phones.getString(it).take(100)})
            })
        }
    }
}
