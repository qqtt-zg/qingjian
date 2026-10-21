//! 静态译词表的查法：个人释义优先，随包表兜底。
//!
//! 桌面端把个人释义表整份下发（`user-glossary-<语言>.tsv`），差集在手机端做——
//! 桌面端要比对随包表就得能读 .qj，得为此写一个读器，而随包表本来就该在手机上。
//! 手机只留「随包表里没有的」那些，合并时个人表优先，与桌面端 LayeredTranslator 的
//! 「个人表优先、随包表兜底」一致。
//!
//! 这里不碰 org.json：传输层的解析放在 GlossaryJson.kt，纯逻辑才能被 JVM 单元测试覆盖。
package org.qingjian.speech.learning.glossary

/** 一条个人释义的原始数据。 */
class PersonalGloss(val language: String, val word: String, val partOfSpeech: String?,
    val text: String, val reading: String?)

/** 桌面端下发的个人释义：词 → 释义。 */
class PersonalGlosses(private val entries: Map<String, Sense>) {
    fun find(word: String): Sense? = entries[word]

    fun size(): Int = entries.size

    fun words(): Set<String> = entries.keys

    companion object {
        /**
         * 取指定语言的个人释义，并**只留随包表里没有的**。
         *
         * 随包表没有（查不到）时全留——没有表可比对，个人释义就是唯一来源。
         */
        fun of(rows: List<PersonalGloss>, language: String, table: Glossary?): PersonalGlosses {
            val personal = mutableMapOf<String, Sense>()
            for (row in rows) {
                if (row.language != language) continue
                val word = row.word.trim()
                val text = row.text.trim()
                if (word.isEmpty() || word.length > 500 || text.isEmpty()) continue
                personal[word] = Sense(row.partOfSpeech?.trim()?.ifEmpty { null }, text.take(500),
                    row.reading?.trim()?.ifEmpty { null })
            }
            if (table == null) return PersonalGlosses(personal)
            return PersonalGlosses(personal.filterKeys { table.find(it) == null })
        }
    }
}

/**
 * 译词查询：个人释义优先，随包表兜底。
 *
 * 随包表查词是精确查（建词时输入中文词）；搜索走前缀，前缀命中的是随包表的词条。
 */
class GlossaryLookup(private val table: Glossary?, private val personal: PersonalGlosses) {
    /** 精确查一个词。个人释义优先。 */
    fun find(word: String): Sense? = personal.find(word) ?: table?.find(word)

    /**
     * 子串搜索。个人释义里命中的排在前面（它们是随包表没有的词），其余是随包表的词条。
     */
    fun search(query: String, limit: Int): List<Pair<String, Sense>> {
        val result = mutableListOf<Pair<String, Sense>>()
        if (limit <= 0) return result
        for (word in personal.words()) {
            if (result.size >= limit) break
            if (word.contains(query)) personal.find(word)?.let { result.add(word to it) }
        }
        for ((word, sense) in table?.search(query, limit - result.size).orEmpty()) {
            if (result.size >= limit) break
            result.add(word to sense)
        }
        return result
    }
}
