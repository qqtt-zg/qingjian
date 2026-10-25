//! 规范化学习事件契约：四维度映射、真实分数、辅助证据与时间精度。
package org.qingjian.speech.learning.archive

enum class EventDimension(val key: String) {
    SPELLING("spelling"),
    LISTENING("listening"),
    MEANING("meaning"),
    PRONUNCIATION("pronunciation"),
    UNKNOWN("unknown");

    companion object {
        fun from(value: String): EventDimension =
            entries.firstOrNull { it.key == value } ?: UNKNOWN
    }
}

enum class EventOutcome(val key: String) {
    GRADED("graded"),
    ASSESSMENT("assessment"),
    SELF_REVIEW("self_review"),
    EXPOSURE("exposure"),
    SKIPPED("skipped"),
    ABANDONED("abandoned")
}

enum class TimePrecision(val key: String) {
    EXACT("exact"),
    DAY_INFERRED("day_inferred")
}

data class NormalizedEvent(
    val eventId: String,
    val wordId: Long,
    val english: String,
    val dimension: EventDimension,
    val occurredAt: Long,
    val day: String,
    val zone: String,
    val timePrecision: TimePrecision = TimePrecision.EXACT,
    val outcome: EventOutcome,
    val correct: Boolean?,
    val assisted: Boolean?,
    val score: Double? = null,
    val provider: String? = null,
    val referenceMode: String? = null,
    val planId: Long? = null,
    val stageKey: String? = null,
    val app: String? = null,
    val rawPayload: String? = null
)
