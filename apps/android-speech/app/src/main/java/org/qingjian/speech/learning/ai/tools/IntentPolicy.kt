//! 只有原问题中的明确需求可以自动触发声音或练习；工具参数不能自授权限。
package org.qingjian.speech.learning.ai.tools

object IntentPolicy {
    fun allows(kind: String, question: String): Boolean {
        val words = when(kind) {
            "request_pronunciation" -> "朗读|读出|听听|听发音|听示范|读一下|播放|pronounce|read aloud|speak|listen"
            "prepare_practice" -> "练习|出题|考考|测验|一道题|practice|quiz|exercise"
            else -> return true
        }
        val denied = Regex("(?i)(?:不要|别|不用|不需要|不想|don't|do not|without)[^，。！？;\\n]{0,18}(?:$words)")
        return Regex("(?i)(?:$words)").containsMatchIn(question) && !denied.containsMatchIn(question)
    }
}
