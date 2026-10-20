//! 成对选择已完成问答；超长回答有明确截断标记，不会挤掉全部历史。
package org.qingjian.speech.learning.ai.memory

import org.qingjian.speech.learning.ChatEntry

object History {
    fun blocks(entries: List<ChatEntry>): List<List<ChatEntry>> {
        val result = mutableListOf<List<ChatEntry>>()
        val questions = entries.filter { it.role == "user" && it.state == "done" }.associateBy { it.id }
        var question: ChatEntry? = null
        for(entry in entries) {
            if(entry.role == "user") question = entry.takeIf { it.state == "done" }
            else if(entry.role == "assistant" && entry.state == "done") {
                val parent = if(entry.replyTo != null) questions[entry.replyTo] else question
                parent?.let { result += listOf(it, entry) }
                // 旧数据库可能只有一条助手讲解，保留兼容而不伪造问题。
                if(parent == null) result += listOf(entry)
                question = null
            }
        }
        return result
    }

    fun recent(entries: List<ChatEntry>): List<ChatEntry> {
        var budget = 16000
        val selected = mutableListOf<List<ChatEntry>>()
        for(block in blocks(entries).takeLast(6).asReversed()) {
            val clipped = block.map { it.copy(text = clip(it.text, if(it.role == "user") 4000 else 7000)) }
            val size = clipped.sumOf { it.text.length }
            if(size > budget) break
            selected += clipped; budget -= size
        }
        return selected.asReversed().flatten()
    }

    fun clip(text: String, limit: Int): String = if(text.length <= limit) text else text.take(limit - 12) + "\n[长消息已截断]"
}
