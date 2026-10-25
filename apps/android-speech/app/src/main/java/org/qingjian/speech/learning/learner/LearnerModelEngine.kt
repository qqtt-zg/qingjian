//! 学习者能力模型投影引擎：确定性将规范化事件流转换为各词各维度的能力快照。
package org.qingjian.speech.learning.learner

import org.qingjian.speech.learning.archive.EventDimension
import org.qingjian.speech.learning.archive.EventOutcome
import org.qingjian.speech.learning.archive.NormalizedEvent
import org.qingjian.speech.learning.daily.ReviewScheduleCore

object LearnerModelEngine {

    const val RULE_VERSION = 1
    private const val DAY_MILLIS = 86400000L

    /**
     * 将全量或增量事件流投影为学习者模型。
     * 输入保证去重与全序排列；相同输入两次运行结果严格一致（纯函数无副作用）。
     */
    fun project(
        events: List<NormalizedEvent>,
        initialEntries: Map<Pair<Long, String>, LearnerModelEntry> = emptyMap(),
        overrides: Map<Pair<Long, String>, UserOverride> = emptyMap()
    ): Map<Pair<Long, String>, LearnerModelEntry> {
        val grouped = events.groupBy { it.wordId to it.dimension.key }
        val result = initialEntries.toMutableMap()

        // 收集所有需要重算的键
        val allKeys = (initialEntries.keys + grouped.keys).distinct()

        for (key in allKeys) {
            val (wordId, dimension) = key
            val wordEvents = grouped[key] ?: emptyList()
            val override = overrides[key]
            result[key] = projectWord(wordId, dimension, wordEvents, initialEntries[key], override)
        }

        return result
    }

    /**
     * 针对单个单词的单个维度执行状态机跃迁。
     */
    fun projectWord(
        wordId: Long,
        dimension: String,
        events: List<NormalizedEvent>,
        initial: LearnerModelEntry? = null,
        override: UserOverride? = null
    ): LearnerModelEntry {
        if (events.isEmpty() && initial != null) {
            return initial
        }

        var attempts = initial?.attempts ?: 0
        var correct = initial?.correct ?: 0
        var assisted = initial?.assisted ?: 0
        var independentAttempts = initial?.independentAttempts ?: 0
        var independentCorrect = initial?.independentCorrect ?: 0
        var streak = initial?.streak ?: 0
        var lastSuccessDay = initial?.lastSuccessDay
        var lastAt = initial?.lastAt
        var nextDue = initial?.nextDue
        var score = initial?.score
        var origin = initial?.origin ?: "phone"
        var state = initial?.state ?: "unknown"

        for (event in events) {
            if (event.dimension.key != dimension || event.wordId != wordId) continue

            // 1. 曝光、自评、跳过、放弃：不计入有效作答，不推进连胜
            if (event.outcome in listOf(
                    EventOutcome.EXPOSURE,
                    EventOutcome.SELF_REVIEW,
                    EventOutcome.SKIPPED,
                    EventOutcome.ABANDONED
                )
            ) {
                lastAt = maxOf(lastAt ?: 0L, event.occurredAt)
                continue
            }

            // 2. 发音评测
            if (event.outcome == EventOutcome.ASSESSMENT) {
                if (event.score == null) {
                    // 未成功或无效发音评测，不记入有效能力 attempts
                    lastAt = maxOf(lastAt ?: 0L, event.occurredAt)
                    continue
                }

                attempts++
                independentAttempts++
                score = event.score
                lastAt = event.occurredAt

                val isSuccess = event.score >= 80.0
                if (isSuccess) {
                    correct++
                    independentCorrect++
                    // 同日抑制
                    if (lastSuccessDay == null) {
                        streak = 1
                        lastSuccessDay = event.day
                    } else if (lastSuccessDay != event.day) {
                        streak++
                        lastSuccessDay = event.day
                    }
                    nextDue = ReviewScheduleCore.calculateNextDue(streak, event.occurredAt)
                    state = if (streak >= 2) "solid" else "learning"
                } else {
                    streak = 0
                    nextDue = event.occurredAt + 1 * DAY_MILLIS
                    state = if (event.score >= 60.0) "shaky" else "learning"
                }
                continue
            }

            // 3. 常规作答 (GRADED)
            if (event.outcome == EventOutcome.GRADED) {
                attempts++
                lastAt = event.occurredAt

                val isCorrect = event.correct == true
                if (isCorrect) correct++

                if (event.assisted == true) {
                    assisted++
                } else if (event.assisted == false) {
                    independentAttempts++
                    if (isCorrect) independentCorrect++
                }

                when (dimension) {
                    EventDimension.SPELLING.key, EventDimension.LISTENING.key -> {
                        if (!isCorrect) {
                            streak = 0
                            state = "learning"
                            nextDue = event.occurredAt + 1 * DAY_MILLIS
                        } else if (event.assisted == true) {
                            state = "shaky"
                            nextDue = event.occurredAt + 1 * DAY_MILLIS
                        } else if (event.assisted == false) {
                            if (lastSuccessDay == null) {
                                streak = 1
                                lastSuccessDay = event.day
                            } else if (lastSuccessDay != event.day) {
                                streak++
                                lastSuccessDay = event.day
                            }
                            nextDue = ReviewScheduleCore.calculateNextDue(streak, event.occurredAt)
                            state = if (streak >= 2) "solid" else "learning"
                        } else {
                            // assisted == null（如旧卡片拼写，独立性未知）：不推进独立连胜
                            state = "learning"
                            nextDue = event.occurredAt + 1 * DAY_MILLIS
                        }
                    }

                    EventDimension.MEANING.key -> {
                        if (!isCorrect) {
                            streak = 0
                            nextDue = event.occurredAt + 1 * DAY_MILLIS
                        } else if (event.assisted == false) {
                            if (lastSuccessDay == null) {
                                streak = 1
                                lastSuccessDay = event.day
                            } else if (lastSuccessDay != event.day) {
                                streak++
                                lastSuccessDay = event.day
                            }
                            nextDue = ReviewScheduleCore.calculateNextDue(streak, event.occurredAt)
                        } else {
                            nextDue = event.occurredAt + 1 * DAY_MILLIS
                        }

                        // meaning 规则：作答满 3 次依据准确率和跨日连胜判定
                        if (independentAttempts < 3) {
                            state = "learning"
                        } else {
                            val rate = independentCorrect.toDouble() / independentAttempts
                            state = when {
                                rate < 0.6 -> "shaky"
                                rate >= 0.8 && streak >= 2 -> "solid"
                                else -> "learning"
                            }
                        }
                    }

                    else -> {
                        if (!isCorrect) {
                            streak = 0
                            state = "learning"
                            nextDue = event.occurredAt + 1 * DAY_MILLIS
                        } else {
                            streak = if (streak == 0) 1 else streak
                            state = "learning"
                            nextDue = ReviewScheduleCore.calculateNextDue(streak, event.occurredAt)
                        }
                    }
                }
            }
        }

        if (attempts == 0) {
            state = "unknown"
            nextDue = null
        }

        // 检查手动覆盖：未答错且处于有效期内
        if (override != null && override.isValid(lastAt ?: System.currentTimeMillis())) {
            state = "solid"
        }

        return LearnerModelEntry(
            wordId = wordId,
            dimension = dimension,
            state = state,
            attempts = attempts,
            correct = correct,
            assisted = assisted,
            independentAttempts = independentAttempts,
            independentCorrect = independentCorrect,
            streak = streak,
            lastSuccessDay = lastSuccessDay,
            lastAt = lastAt,
            nextDue = nextDue,
            score = score,
            origin = origin,
            ruleVersion = RULE_VERSION
        )
    }
}
