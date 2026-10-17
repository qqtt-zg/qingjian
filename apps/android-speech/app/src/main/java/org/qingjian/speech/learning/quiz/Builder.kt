//! 从用户笔记生成最多五道离线题；拼写跳过相同笔记对应多个词的歧义条目。
package org.qingjian.speech.learning.quiz

import org.qingjian.speech.learning.VocabularyEntry
import java.util.UUID
import kotlin.random.Random

object Builder {
    fun build(words: List<VocabularyEntry>, kind: Kind, selected: Set<Long>? = null, random: Random = Random.Default): List<Question> {
        val noted = words.filter { it.meaning.isNotBlank() }
        val meanings = noted.groupBy { Question.normalize(it.meaning) }
        val available = when(kind) {
            Kind.CHOICE -> if(meanings.size >= 2) noted else emptyList()
            Kind.SPELLING -> noted.filter { meanings[Question.normalize(it.meaning)]!!.size == 1 && it.english.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }
        }.filter { selected == null || it.id in selected }
        return available.shuffled(random).take(5).map { word ->
            val options = if(kind == Kind.CHOICE) {
                val others = noted.filter { Question.normalize(it.meaning) != Question.normalize(word.meaning) }
                    .distinctBy { Question.normalize(it.meaning) }.shuffled(random).take(3).map { it.meaning }
                (others + word.meaning).shuffled(random)
            } else emptyList()
            Question(UUID.randomUUID().toString(), word.id, word.english, word.meaning, kind, options)
        }
    }
}
