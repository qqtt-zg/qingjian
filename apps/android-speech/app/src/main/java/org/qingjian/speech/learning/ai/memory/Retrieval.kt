//! 从真实词库和最近仍答错的题目中进行有界字面检索，不引入向量模型或虚构掌握程度。
package org.qingjian.speech.learning.ai.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.practice.PracticeStore
import java.util.Locale

class Retrieval(context: Context) {
    private val store = LearningStore.get(context)
    private val practice = PracticeStore(context)

    fun find(question: String): JSONObject {
        val tokens = Regex("[a-zA-Z][a-zA-Z'-]{1,39}|[\\p{IsHan}]{2,12}").findAll(question)
            .map { it.value.lowercase(Locale.ROOT) }.filterNot { it in ignored }.distinct().take(8).toList()
        val words = tokens.flatMap { store.words(it).take(4) }.distinctBy { it.id }.take(4)
        val ids = words.map { it.id }.toSet()
        val reviewing = listOf("错题", "复习", "review", "mistake").any { question.contains(it, true) }
        val mistakes = practice.mistakes().filter { reviewing || it.wordId in ids }.take(3)
        return JSONObject().put("matching_words", JSONArray(words.map {
            JSONObject().put("word_id", it.id).put("english", it.english).put("user_note", it.meaning.take(500))
                .put("completed_playbacks", it.played).put("self_review_count", it.reviewed)
        })).put("recent_mistakes", JSONArray(mistakes.map {
            JSONObject().put("word_id", it.wordId).put("english", it.english).put("kind", it.kind.name)
                .put("user_note", it.meaning.take(300)).put("user_answer", it.answer.take(200))
        }))
    }

    companion object {
        private val ignored = setOf("the", "and", "for", "you", "this", "that", "with", "what", "how", "please", "can", "could", "would", "are", "was", "have", "does", "from", "into")
    }
}
