//! 计划预算与硬约束的单元测试：验证 Credit 计算公式、负荷系数推导、到期预留目标及非法计划校验。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanConstraintsTest {

    @Test
    fun testUsableCreditFormulas() {
        // 10 分钟：
        // factor 1.0 -> floor(10 * 2 * 0.9 * 1.0) = 18
        assertEquals(18, PlanConstraints.calculateUsableCredit(10, 1.0))
        // factor 0.8 -> floor(10 * 2 * 0.9 * 0.8) = 14
        assertEquals(14, PlanConstraints.calculateUsableCredit(10, 0.8))
        // factor 1.1 -> floor(10 * 2 * 0.9 * 1.1) = 19
        assertEquals(19, PlanConstraints.calculateUsableCredit(10, 1.1))

        // 15 分钟:
        // factor 1.0 -> floor(15 * 2 * 0.9 * 1.0) = 27
        assertEquals(27, PlanConstraints.calculateUsableCredit(15, 1.0))

        // 20 分钟:
        // factor 1.0 -> floor(20 * 2 * 0.9 * 1.0) = 36
        assertEquals(36, PlanConstraints.calculateUsableCredit(20, 1.0))

        // 2 分钟极小预算:
        // factor 1.0 -> floor(2 * 2 * 0.9 * 1.0) = 3
        assertEquals(3, PlanConstraints.calculateUsableCredit(2, 1.0))
    }

    @Test
    fun testDueTargetAndCap() {
        val usable = 18
        // dueTarget = ceil(18 * 0.3) = 6
        assertEquals(6, PlanConstraints.calculateDueTarget(usable))
        // dueCap = floor(18 * 0.6) = 10
        assertEquals(10, PlanConstraints.calculateDueCap(usable))
    }

    @Test
    fun testNewWordsCapCalculations() {
        val usable = 18
        // 偏好 5，非入门 -> min(5, floor(18/3), 8) = min(5, 6, 8) = 5
        assertEquals(5, PlanConstraints.calculateNewWordsCap(5, usable, isBeginner = false))

        // 偏好 5，入门 -> floor(5 * 0.8) = 4
        assertEquals(4, PlanConstraints.calculateNewWordsCap(5, usable, isBeginner = true))

        // 极小预算 usable=3，入门 -> floor(min(5, 1, 8) * 0.8) = 0 -> coerceAtLeast(1) = 1
        assertEquals(1, PlanConstraints.calculateNewWordsCap(5, 3, isBeginner = true))

        // 新鲜数据仅 1~5 词 -> 限制最多 3 词
        assertEquals(3, PlanConstraints.calculateNewWordsCap(5, usable, isBeginner = false, freshCount = 4))

        // 新鲜数据 6~20 词 -> 限制最多 2 词
        assertEquals(2, PlanConstraints.calculateNewWordsCap(5, usable, isBeginner = false, freshCount = 12))
    }

    @Test
    fun testFactorDerivationRules() {
        // 1. 不足 3 天 -> 1.0
        val historyShort = listOf(
            PastDayPlanRecord("2026-10-23", 0, 2, "DONE", 18, 18, 100000L, true, 1),
            PastDayPlanRecord("2026-10-24", 0, 2, "DONE", 18, 18, 100000L, true, 1)
        )
        val (fShort, _) = ContextPack.deriveFactor(historyShort, 1)
        assertEquals(1.0, fShort, 0.001)

        // 2. 满 3 天但完成率 < 0.5 -> 0.8
        val historyLow = listOf(
            PastDayPlanRecord("2026-10-22", 0, 2, "DONE", 18, 5, 100000L, true, 1),
            PastDayPlanRecord("2026-10-23", 0, 2, "DONE", 18, 6, 100000L, true, 1),
            PastDayPlanRecord("2026-10-24", 0, 2, "ABANDONED", 18, 2, 50000L, true, 1)
        )
        val (fLow, rLow) = ContextPack.deriveFactor(historyLow, 1)
        assertEquals(0.8, fLow, 0.001)
        assertTrue(rLow.contains("少"))

        // 3. 满 3 天且全部满额极速完成 -> 1.1
        // 18 credit * 30_000 * 0.6 = 324,000 ms
        val historyFast = listOf(
            PastDayPlanRecord("2026-10-22", 0, 2, "DONE", 18, 18, 200000L, true, 1),
            PastDayPlanRecord("2026-10-23", 0, 2, "DONE", 18, 18, 180000L, true, 1),
            PastDayPlanRecord("2026-10-24", 0, 2, "DONE", 18, 18, 190000L, true, 1)
        )
        val (fFast, rFast) = ContextPack.deriveFactor(historyFast, 1)
        assertEquals(1.1, fFast, 0.001)
        assertTrue(rFast.contains("挑战"))
    }

    @Test
    fun testPlanValidationCatchesViolations() {
        val limits = PlanLimits(
            usableCredit = 18,
            dueTarget = 6,
            dueCap = 10,
            newWordCap = 3,
            maxItems = 10,
            maxStagesPerWord = 2
        )

        // 正常计划通过校验
        val validItem = ProposedItem(
            wordId = 1L,
            english = "candidate",
            meaning = "候选人",
            example = "",
            translation = "",
            reasonCode = "due_today",
            reasonText = "今日到期",
            stages = listOf(ProposedStage("REVIEW", "meaning", 1))
        )
        val validPlan = ProposedPlan("今日学习", "理由", listOf(validItem), 1)
        assertTrue(PlanConstraints.validate(validPlan, limits) is ValidationResult.Success)

        // 超出 10 项被拒
        val elevenItems = (1L..11L).map { id ->
            validItem.copy(wordId = id, english = "word$id")
        }
        val planEleven = ProposedPlan("超项计划", "", elevenItems, 11)
        assertTrue(PlanConstraints.validate(planEleven, limits) is ValidationResult.Fail)

        // 单词超过 2 环节被拒
        val itemThreeStages = validItem.copy(
            stages = listOf(
                ProposedStage("LEARN", "meaning", 2),
                ProposedStage("PRACTICE_BASIC", "spelling", 1),
                ProposedStage("REVIEW", "meaning", 1)
            )
        )
        val planThreeStages = ProposedPlan("超环节", "", listOf(itemThreeStages), 4)
        assertTrue(PlanConstraints.validate(planThreeStages, limits) is ValidationResult.Fail)

        // 重复单词被拒
        val duplicateWords = listOf(validItem, validItem)
        val planDup = ProposedPlan("重复单词", "", duplicateWords, 2)
        assertTrue(PlanConstraints.validate(planDup, limits) is ValidationResult.Fail)

        // 超出总预算被拒
        val expensiveItem = validItem.copy(
            stages = listOf(ProposedStage("WEAK_PRACTICE", "spelling", 20))
        )
        val planExpensive = ProposedPlan("超预算", "", listOf(expensiveItem), 20)
        assertTrue(PlanConstraints.validate(planExpensive, limits) is ValidationResult.Fail)
    }
}
