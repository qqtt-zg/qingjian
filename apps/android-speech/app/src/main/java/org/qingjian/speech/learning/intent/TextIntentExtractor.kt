//! 文本待练意图抽取引擎：支持中文句型模式提取与维度推导，具备最小权限安全沙箱防篡改保护。
package org.qingjian.speech.learning.intent

import java.util.Locale

object TextIntentExtractor {

    private val INTENT_PATTERNS = listOf(
        Regex("""(?:我想|我要|想|准备|希望能?)(?:练习|复习|练|学|背|看看|默写|听写|朗读)(?:一下|一练|一遍|点)?\s*(?:口语|拼写|听力|听写|词义|意思|朗读|默写)?\s*(?:的)?\s*([a-zA-Z][a-zA-Z0-9'-]{0,99})"""),
        Regex("""(?:把|将)\s*([a-zA-Z][a-zA-Z0-9'-]{0,99})\s*(?:加入|放进|加到)(?:待练|复习|练习|计划)"""),
        Regex("""(?:重点|专门|针对)?(?:练习|复习|学|练|默写|听写|朗读)(?:一下|一练|一遍|点)?\s*(?:口语|拼写|听力|听写|词义|意思|朗读|默写)?\s*(?:的)?\s*([a-zA-Z][a-zA-Z0-9'-]{0,99})"""),
        Regex("""(?:practice|review|learn|study)\s+([a-zA-Z][a-zA-Z0-9'-]{0,99})""", RegexOption.IGNORE_CASE)
    )

    private val FORBIDDEN_WORDS = setOf(
        "drop", "delete", "update", "select", "insert", "table", "from", "where",
        "ignore", "system", "preference", "preferences", "mastered"
    )

    /**
     * 纯函数：从自然语言文本中提取显式表达的待练英文词条及期望考察维度。
     * 若包含恶意注入或无合法语义，安全返回 null。
     * 遵循最小权限安全设计：纯数据返回，绝无权读取或篡改用户偏好与能力模型成绩。
     */
    fun extract(rawText: String): ExtractedIntent? {
        if (rawText.isBlank()) return null
        val input = rawText.take(500)

        // 检索待练词汇
        for (pattern in INTENT_PATTERNS) {
            val match = pattern.find(input) ?: continue
            val word = match.groupValues[1].trim()
            if (!isValidWord(word)) continue

            val dimension = resolveDimension(input)
            return ExtractedIntent(
                english = word,
                dimension = dimension,
                rawText = input,
                confidence = 0.95
            )
        }
        return null
    }

    /**
     * 生成规范的意图去重键。按日分桶保证同日同一词汇不重复堆积。
     */
    fun generateIntentKey(source: String, english: String, nowMillis: Long = System.currentTimeMillis()): String {
        val epochDay = nowMillis / 86400000L
        val cleanWord = english.trim().lowercase(Locale.ROOT)
        return "$source:$cleanWord:$epochDay"
    }

    private fun isValidWord(word: String): Boolean {
        if (word.length !in 1..100) return false
        if (word.lowercase(Locale.ROOT) in FORBIDDEN_WORDS) return false
        // 允许字母、连字符和单引号
        return word.matches(Regex("""^[a-zA-Z][a-zA-Z0-9'-]{0,99}$"""))
    }

    private fun resolveDimension(text: String): String {
        return when {
            text.contains("拼写") || text.contains("默写") -> "spelling"
            text.contains("发音") || text.contains("读音") || text.contains("口语") || text.contains("朗读") -> "pronunciation"
            text.contains("听力") || text.contains("听写") -> "listening"
            text.contains("词义") || text.contains("意思") || text.contains("解释") || text.contains("中文") -> "meaning"
            else -> ""
        }
    }
}
