//! 本机词汇条目；收到请求、完整播放与自评复习分别计数。
package org.qingjian.speech.learning

data class VocabularyEntry(
    val id: Long,
    val english: String,
    val meaning: String,
    val received: Int,
    val played: Int,
    val reviewed: Int,
    val lastSeen: Long,
    val glossOrigin: String = "unknown",
)
