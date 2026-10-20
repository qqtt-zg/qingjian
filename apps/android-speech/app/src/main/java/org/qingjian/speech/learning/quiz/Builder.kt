//! 从用户笔记生成最多五道离线题；拼写跳过相同笔记对应多个词的歧义条目。
package org.qingjian.speech.learning.quiz

import org.qingjian.speech.learning.VocabularyEntry
import java.util.UUID
import kotlin.random.Random

object Builder {
    fun build(words: List<VocabularyEntry>, kind: Kind, selected: Set<Long>? = null, random: Random = Random.Default, examples: Map<Long, String> = emptyMap()): List<Question> {
        val noted = words.filter { it.meaning.isNotBlank() }
        val meanings = noted.groupBy { Question.normalize(it.meaning) }
        val available = when(kind) {
            Kind.CHOICE -> if(meanings.size >= 2) noted else emptyList()
            Kind.SPELLING -> noted.filter { meanings[Question.normalize(it.meaning)]!!.size == 1 && it.english.any { c -> c in 'a'..'z' || c in 'A'..'Z' } }
            Kind.ENGLISH_CHOICE -> if(noted.distinctBy { Question.normalize(it.english) }.size >= 2) noted.filter { meanings[Question.normalize(it.meaning)]!!.size == 1 } else emptyList()
            Kind.CLOZE -> if(noted.size >= 2) noted.filter { gap(it, examples[it.id]).isNotBlank() } else emptyList()
        }.filter { selected == null || it.id in selected }
        return available.shuffled(random).take(5).map { word ->
            val options = if(kind == Kind.CHOICE) {
                val others = noted.filter { Question.normalize(it.meaning) != Question.normalize(word.meaning) }
                    .distinctBy { Question.normalize(it.meaning) }.shuffled(random).take(3).map { it.meaning }
                (others + word.meaning).shuffled(random)
            } else if(kind in listOf(Kind.ENGLISH_CHOICE, Kind.CLOZE)) {
                (noted.filter { Question.normalize(it.english) != Question.normalize(word.english) }
                    .distinctBy { Question.normalize(it.english) }.shuffled(random).take(3).map { it.english } + word.english).shuffled(random)
            } else emptyList()
            Question(UUID.randomUUID().toString(), word.id, word.english, word.meaning, kind, options, if(kind == Kind.CLOZE) gap(word, examples[word.id]) else "")
        }
    }

    private fun gap(word: VocabularyEntry, example: String?): String {
        if(example.isNullOrBlank() || example.length > 500) return ""
        val pattern = Regex("(?i)(?<![A-Za-z])" + Regex.escape(word.english) + "(?![A-Za-z])")
        if(pattern.findAll(example).count() != 1) return ""
        return pattern.replaceFirst(example, "____")
    }
}
