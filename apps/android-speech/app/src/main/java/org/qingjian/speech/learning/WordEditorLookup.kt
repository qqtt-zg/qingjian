//! 词条编辑的中英文双向查词与来源解析规则：纯逻辑，不依赖 Android UI。
package org.qingjian.speech.learning

import org.qingjian.speech.learning.glossary.Sense

data class WordEditorResult(
    val english: String,
    val meaning: String,
    val origin: String,
    val hintText: String,
    val isChineseInput: Boolean,
    val matchFound: Boolean
)

object WordEditorLookup {
    fun hasChinese(text: String): Boolean = text.any { it in '㐀'..'鿿' }

    /**
     * 根据输入解析单词主键、释义和来源：
     * - 输入中文（如「候选」）：查中->英表（glossary-en），英文作主键，中文作释义，来源 static-table；
     * - 输入英文：查英->中表（glossary-zh），自动填入中文释义，来源 static-table；
     * - 两向查不到：保留用户输入，来源 unknown。
     */
    fun resolve(
        input: String,
        userMeaning: String,
        lookupZhToEn: (String) -> Sense?,
        lookupEnToZh: (String) -> Sense?
    ): WordEditorResult {
        val trimmed = input.trim()
        val trimmedMeaning = userMeaning.trim()
        if (trimmed.isEmpty()) {
            return WordEditorResult(
                english = "",
                meaning = trimmedMeaning,
                origin = "unknown",
                hintText = "支持输入中文查英文，或输入英文查中文释义",
                isChineseInput = false,
                matchFound = false
            )
        }

        val isChinese = hasChinese(trimmed)
        if (isChinese) {
            val sense = lookupZhToEn(trimmed)
            return if (sense != null) {
                val pos = sense.partOfSpeech?.let { "$it " }.orEmpty()
                val finalMeaning = if (trimmedMeaning.isNotBlank()) trimmedMeaning else "$pos$trimmed"
                WordEditorResult(
                    english = sense.text.trim(),
                    meaning = finalMeaning,
                    origin = "static-table",
                    hintText = "匹配到英文：${sense.text} (${pos.trim()})",
                    isChineseInput = true,
                    matchFound = true
                )
            } else {
                WordEditorResult(
                    english = "",
                    meaning = trimmedMeaning,
                    origin = "unknown",
                    hintText = "词典中未收录该中文，需手动指定英文单词",
                    isChineseInput = true,
                    matchFound = false
                )
            }
        } else {
            val sense = lookupEnToZh(trimmed)
            return if (sense != null) {
                val pos = sense.partOfSpeech?.let { "$it " }.orEmpty()
                val finalMeaning = if (trimmedMeaning.isNotBlank()) trimmedMeaning else "$pos${sense.text}"
                WordEditorResult(
                    english = trimmed,
                    meaning = finalMeaning,
                    origin = "static-table",
                    hintText = "释义推荐：$pos${sense.text}",
                    isChineseInput = false,
                    matchFound = true
                )
            } else {
                WordEditorResult(
                    english = trimmed,
                    meaning = trimmedMeaning,
                    origin = "unknown",
                    hintText = "英汉词典未命中，可直接保存或留给 AI 补全",
                    isChineseInput = false,
                    matchFound = false
                )
            }
        }
    }
}
