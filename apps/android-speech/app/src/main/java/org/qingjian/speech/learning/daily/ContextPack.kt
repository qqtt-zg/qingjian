//! 不可变学习上下文快照：包含日期、预算、四类候选池与静态材料可用性。
package org.qingjian.speech.learning.daily

import org.qingjian.speech.learning.learner.EffectiveDue
import org.qingjian.speech.learning.learner.LearnerModelEntry
import org.qingjian.speech.learning.learner.UserOverride

data class CandidateDimension(
    val dimension: String,
    val state: String,
    val effectiveDue: Long?,
    val overdueDays: Double = 0.0,
    val recentErrors: Int = 0,
    val assistedCount: Int = 0,
    val lastAt: Long? = null
)

data class CandidateWord(
    val wordId: Long,
    val english: String,
    val meaning: String,
    val glossOrigin: String,
    val dimensions: List<CandidateDimension>,
    val desktopCommitted: Int = 0,
    val desktopLastSeenMillis: Long = 0L,
    val desktopKnown: Boolean = false,
    val phoneErrorRate: Double? = null,
    val baseScore: Double = 0.0,
    val length: Int = english.length
)

data class CandidatePools(
    val due: List<CandidateWord>,
    val weak: List<CandidateWord>,
    val fresh: List<CandidateWord>,
    val consolidate: List<CandidateWord>
)

data class BudgetSnapshot(
    val minutes: Int,
    val factor: Double,
    val factorReason: String,
    val usableCredit: Int,
    val questionLimit: Int? = null
)

data class MaterialStatus(
    val wordId: Long,
    val hasMeaning: Boolean,
    val hasExample: Boolean,
    val hasTranslation: Boolean,
    val hasClozeGap: Boolean,
    val distractorCount: Int,
    val hasAudio: Boolean
)

data class PastDayPlanRecord(
    val day: String,
    val roundNo: Int,
    val formatVersion: Int,
    val status: String,
    val plannedCredit: Int,
    val doneCredit: Int,
    val activeMs: Long,
    val measurementValid: Boolean,
    val preferencesRevision: Int,
    val questionLimit: Int = 0
)

data class ContextPack(
    val today: String,
    val epoch: Long,
    val preferencesRevision: Int,
    val desktopRevision: Long,
    val modelRevision: Int,
    val budget: BudgetSnapshot,
    val candidates: CandidatePools,
    val materials: Map<Long, MaterialStatus> = emptyMap(),
    val totalWords: Int = 0
) {
    companion object {
        const val DAY_MILLIS = 86400000.0

        /**
         * 纯函数：根据历史计划推导负荷系数 0.8 / 1.0 / 1.1 及原因。
         */
        fun deriveFactor(
            history: List<PastDayPlanRecord>,
            currentPreferencesRevision: Int
        ): Pair<Double, String> {
            // 筛选条件：匹配当前偏好修订、属于默认轮次(round_no=0)、排除单题限制(questionLimit==0)、且已结束
            val eligible = history.filter {
                it.preferencesRevision == currentPreferencesRevision &&
                    it.roundNo == 0 &&
                    it.questionLimit == 0 &&
                    (it.status in listOf("DONE", "ABANDONED") || (it.formatVersion == 1 && it.status == "DONE")) &&
                    it.plannedCredit > 0
            }

            // 按不同日期取最近 3 天
            val distinctDays = eligible.distinctBy { it.day }.sortedByDescending { it.day }.take(3)
            if (distinctDays.size < 3) {
                return 1.0 to "刚调整过偏好或天数不足，按基准安排"
            }

            val totalDone = distinctDays.sumOf { it.doneCredit }
            val totalPlanned = distinctDays.sumOf { it.plannedCredit }
            if (totalPlanned > 0 && (totalDone.toDouble() / totalPlanned) < 0.5) {
                return 0.8 to "最近完成得少，今天少安排一点"
            }

            val allFullyCompleted = distinctDays.all { it.doneCredit >= it.plannedCredit }
            val allMeasurementValid = distinctDays.all { it.measurementValid }
            val allFast = distinctDays.all { it.activeMs > 0 && it.activeMs < it.plannedCredit * 30_000L * 0.6 }

            if (allFullyCompleted && allMeasurementValid && allFast) {
                return 1.1 to "最近学得又快又稳，适当增加一点挑战"
            }

            return 1.0 to "按标准进度安排"
        }

        /**
         * 纯函数计算电脑常见词得分：
         * base_score = 0.50 * min(log2(1 + committed) / 6, 1) + 0.30 * recency + 0.20 * error_rate
         */
        fun calculateBaseScore(
            committed: Int,
            lastSeenMillis: Long,
            nowMillis: Long,
            phoneErrorRate: Double?
        ): Double {
            val committedScore = if (committed > 0) {
                val logVal = kotlin.math.ln(1.0 + committed) / kotlin.math.ln(2.0)
                minOf(logVal / 6.0, 1.0)
            } else 0.0

            val recencyScore = if (lastSeenMillis > 0 && nowMillis >= lastSeenMillis) {
                val daysAgo = (nowMillis - lastSeenMillis) / 86400000.0
                maxOf(0.0, 1.0 - (daysAgo / 30.0))
            } else 0.0

            val errorScore = phoneErrorRate?.coerceIn(0.0, 1.0)

            // 权重：0.50 committed, 0.30 recency, 0.20 errorRate
            var weightSum = 0.50 + 0.30
            var scoreSum = 0.50 * committedScore + 0.30 * recencyScore
            if (errorScore != null) {
                weightSum += 0.20
                scoreSum += 0.20 * errorScore
            }

            return if (weightSum > 0) scoreSum / weightSum else 0.0
        }
    }
}
