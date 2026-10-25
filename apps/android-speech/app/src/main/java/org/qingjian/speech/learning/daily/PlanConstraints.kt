//! 计划预算与硬约束校验器：1 Credit = 30 秒，负责计算可用限额、到期/新词配额以及排程合法性检验。
package org.qingjian.speech.learning.daily

data class ProposedStage(
    val stageType: String,      // "LEARN", "PRACTICE_BASIC", "REVIEW", "WEAK_PRACTICE", "CONSOLIDATE_PRACTICE", "ORAL"
    val dimension: String,      // "spelling", "listening", "meaning", "pronunciation"
    val credit: Int,
    val questionSnapshot: String = "",
    val listening: Boolean = false
)

data class ProposedItem(
    val wordId: Long,
    val english: String,
    val meaning: String,
    val example: String,
    val translation: String,
    val reasonCode: String,     // "due_...", "weak_...", "fresh_...", "consolidate_..."
    val reasonText: String,
    val stages: List<ProposedStage>
) {
    val totalCredit get() = stages.sumOf { it.credit }
}

data class ProposedPlan(
    val title: String,
    val reason: String,
    val items: List<ProposedItem>,
    val plannedCredit: Int,
    val budgetSnapshotJson: String = "{}"
)

data class PlanLimits(
    val usableCredit: Int,
    val dueTarget: Int,
    val dueCap: Int,
    val newWordCap: Int,
    val maxItems: Int = 10,
    val maxStagesPerWord: Int = 2,
    val questionLimit: Int? = null
)

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Fail(val reason: String) : ValidationResult()
}

object PlanConstraints {
    const val CREDIT_PER_MINUTE = 2
    const val BUFFER_RATIO = 0.9
    const val MAX_ITEMS_PER_ROUND = 10
    const val MAX_STAGES_PER_WORD = 2

    // 各环节标准 Credit
    const val CREDIT_LEARN = 2
    const val CREDIT_PRACTICE_BASIC = 1
    const val CREDIT_REVIEW = 1
    const val CREDIT_WEAK_PRACTICE = 2
    const val CREDIT_CONSOLIDATE_PRACTICE = 1
    const val CREDIT_ORAL = 2

    /** 纯函数计算可用 Credit: floor(minutes * 2 * 0.9 * factor) */
    fun calculateUsableCredit(minutes: Int, factor: Double): Int =
        kotlin.math.floor(minutes * CREDIT_PER_MINUTE * BUFFER_RATIO * factor).toInt()

    /** 到期预留目标: ceil(usable * 0.3) */
    fun calculateDueTarget(usable: Int): Int =
        kotlin.math.ceil(usable * 0.3).toInt()

    /** 到期硬上限: floor(usable * 0.6) */
    fun calculateDueCap(usable: Int): Int =
        kotlin.math.floor(usable * 0.6).toInt()

    /** 新词上限计算 */
    fun calculateNewWordsCap(
        preferenceNewWords: Int,
        usable: Int,
        isBeginner: Boolean,
        freshCount: Int = Int.MAX_VALUE,
        freshOnlyDesktop: Boolean = false
    ): Int {
        var base = minOf(preferenceNewWords, kotlin.math.floor(usable / 3.0).toInt(), 8)
        if (isBeginner) {
            base = kotlin.math.floor(base * 0.8).toInt().coerceAtLeast(if (usable >= 3) 1 else 0)
        }
        if (freshCount in 1..5) {
            base = minOf(base, 3)
        }
        if (freshOnlyDesktop) {
            base = minOf(base, 3)
        }
        if (freshCount in 6..20) {
            base = minOf(base, 2)
        }
        return base
    }

    fun resolveLimits(
        usableCredit: Int,
        preferenceNewWords: Int,
        isBeginner: Boolean,
        freshCount: Int = Int.MAX_VALUE,
        freshOnlyDesktop: Boolean = false,
        questionLimit: Int? = null
    ): PlanLimits {
        return PlanLimits(
            usableCredit = usableCredit,
            dueTarget = calculateDueTarget(usableCredit),
            dueCap = calculateDueCap(usableCredit),
            newWordCap = calculateNewWordsCap(preferenceNewWords, usableCredit, isBeginner, freshCount, freshOnlyDesktop),
            maxItems = MAX_ITEMS_PER_ROUND,
            maxStagesPerWord = MAX_STAGES_PER_WORD,
            questionLimit = questionLimit
        )
    }

    /** 整体计划合法性校验 */
    fun validate(plan: ProposedPlan, limits: PlanLimits): ValidationResult {
        if (plan.items.size > limits.maxItems) {
            return ValidationResult.Fail("计划项数 (${plan.items.size}) 超过上限 ${limits.maxItems}")
        }

        val wordIds = plan.items.map { it.wordId }
        if (wordIds.distinct().size != wordIds.size) {
            return ValidationResult.Fail("计划中存在重复词汇 ID")
        }

        for (item in plan.items) {
            if (item.stages.size > limits.maxStagesPerWord) {
                return ValidationResult.Fail("词条 ${item.english} 包含 ${item.stages.size} 个环节，超过上限 ${limits.maxStagesPerWord}")
            }
        }

        val totalCredit = plan.items.sumOf { it.totalCredit }
        if (totalCredit > limits.usableCredit) {
            return ValidationResult.Fail("计划总 Credit ($totalCredit) 超过可用预算 (${limits.usableCredit})")
        }

        // 到期上限检查 (reasonCode 开头为 due)
        val dueCredit = plan.items.filter { it.reasonCode.startsWith("due") }.sumOf { it.totalCredit }
        if (dueCredit > limits.dueCap) {
            return ValidationResult.Fail("到期项 Credit ($dueCredit) 超过上限 (${limits.dueCap})")
        }

        // 新词上限检查 (reasonCode 开头为 fresh)
        val freshCount = plan.items.count { it.reasonCode.startsWith("fresh") }
        if (freshCount > limits.newWordCap) {
            return ValidationResult.Fail("新词数量 ($freshCount) 超过上限 (${limits.newWordCap})")
        }

        // 题目硬限制检查 (非 LEARN、非 REVIEW)
        if (limits.questionLimit != null && limits.questionLimit > 0) {
            val questionStagesCount = plan.items.flatMap { it.stages }.count {
                it.stageType !in listOf("LEARN", "REVIEW")
            }
            if (questionStagesCount > limits.questionLimit) {
                return ValidationResult.Fail("题目环节数 ($questionStagesCount) 超过用户限制 (${limits.questionLimit})")
            }
        }

        return ValidationResult.Success
    }
}
