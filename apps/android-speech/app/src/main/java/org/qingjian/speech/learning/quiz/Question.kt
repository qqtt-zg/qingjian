//! 一道测验的固定快照和唯一标识，重建页面后仍对应同一次作答。
package org.qingjian.speech.learning.quiz

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Question(
    val key: String,

    val wordId: Long,

    val english: String,

    val meaning: String,

    val kind: Kind,

    val options: List<String>,
) {
    fun correct(answer: String) = if(kind == Kind.SPELLING) normalize(answer) == normalize(english) else answer == meaning

    fun json(): JSONObject = JSONObject().put("key", key).put("word", wordId).put("english", english)
        .put("meaning", meaning).put("kind", kind.name).put("options", JSONArray(options))

    companion object {
        fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace('’', '\'').replace(Regex("\\s+"), " ")

        fun from(value: JSONObject): Question {
            val options = value.getJSONArray("options")
            val result = Question(value.getString("key"), value.getLong("word"), value.getString("english"),
                value.getString("meaning"), Kind.valueOf(value.getString("kind")), (0 until options.length()).map { options.getString(it) })
            require(result.key.matches(Regex("[a-f0-9-]{36}")) && result.wordId > 0)
            require(result.english.length in 1..500 && result.meaning.length in 1..1000)
            require(result.options.size <= 4 && result.options.all { it.length in 1..1000 })
            if(result.kind == Kind.CHOICE) require(result.options.size >= 2 && result.meaning in result.options)
            return result
        }
    }
}
