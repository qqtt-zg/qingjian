//! 跨端与会话待练意图领域模型；记录来源、去重键与 30 天生命周期。
package org.qingjian.speech.learning.intent

data class StudyIntent(
    val id: Long,
    val intentKey: String,
    val wordId: Long,
    val english: String,
    val targetDimension: String,
    val source: String,          // "desktop", "chat", "manual"
    val status: String,          // "pending", "scheduled", "consumed", "expired", "cancelled"
    val createdAt: Long,
    val expiresAt: Long,
    val scheduledPlanId: Long = 0L,
    val consumedAt: Long = 0L,
    val priority: Int = 0
)

data class DesktopIntent(
    val id: String,
    val english: String,
    val meaning: String = "",
    val dimension: String = "",
    val source: String = "desktop",
    val createdAt: Long,
    val expiresAt: Long
)

data class ExtractedIntent(
    val english: String,
    val dimension: String = "",
    val rawText: String = "",
    val confidence: Double = 1.0
)
