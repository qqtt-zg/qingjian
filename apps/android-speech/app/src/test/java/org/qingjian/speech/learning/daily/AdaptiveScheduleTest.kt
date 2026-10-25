//! 自适应排程与负荷系数派生的单元测试：验证 3 天低完成率降速、有效测时加速、偏好变更重置及用户硬约束保护。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveScheduleTest {

    @Test
    fun testThreeDayLowCompletionDropsToZeroPointEight() {
        val history = listOf(
            PastDayPlanRecord("2026-10-22", 0, 2, "DONE", plannedCredit = 20, doneCredit = 8, activeMs = 60000L, measurementValid = true, preferencesRevision = 1),
            PastDayPlanRecord("2026-10-23", 0, 2, "DONE", plannedCredit = 20, doneCredit = 6, activeMs = 50000L, measurementValid = true, preferencesRevision = 1),
            PastDayPlanRecord("2026-10-24", 0, 2, "ABANDONED", plannedCredit = 20, doneCredit = 4, activeMs = 40000L, measurementValid = true, preferencesRevision = 1)
        )
        // 总完成 18 / 60 < 0.5 -> 负荷降为 0.8
        val (factor, reason) = ContextPack.deriveFactor(history, currentPreferencesRevision = 1)
        assertEquals(0.8, factor, 0.001)
        assertTrue(reason.contains("少安排一点"))
    }

    @Test
    fun testValidFastMeasurementAcceleratesToOnePointOne() {
        // 满额完成 + 测时有效 + activeMs < plannedCredit * 30_000 * 0.6 (即 < 18 * 18,000 = 324,000 ms)
        val history = listOf(
            PastDayPlanRecord("2026-10-22", 0, 2, "DONE", plannedCredit = 18, doneCredit = 18, activeMs = 200000L, measurementValid = true, preferencesRevision = 1),
            PastDayPlanRecord("2026-10-23", 0, 2, "DONE", plannedCredit = 18, doneCredit = 18, activeMs = 210000L, measurementValid = true, preferencesRevision = 1),
            PastDayPlanRecord("2026-10-24", 0, 2, "DONE", plannedCredit = 18, doneCredit = 18, activeMs = 190000L, measurementValid = true, preferencesRevision = 1)
        )
        val (factor, reason) = ContextPack.deriveFactor(history, currentPreferencesRevision = 1)
        assertEquals(1.1, factor, 0.001)
        assertTrue(reason.contains("挑战"))
    }

    @Test
    fun testPreferencesRevisionChangeResetsToOnePointZero() {
        val history = listOf(
            PastDayPlanRecord("2026-10-22", 0, 2, "DONE", 18, 5, 60000L, true, preferencesRevision = 1),
            PastDayPlanRecord("2026-10-23", 0, 2, "DONE", 18, 6, 50000L, true, preferencesRevision = 1),
            PastDayPlanRecord("2026-10-24", 0, 2, "DONE", 18, 4, 40000L, true, preferencesRevision = 1)
        )
        // 用户调整了偏好 (preferencesRevision = 2)，旧版本历史不匹配，安全重置为 1.0
        val (factor, reason) = ContextPack.deriveFactor(history, currentPreferencesRevision = 2)
        assertEquals(1.0, factor, 0.001)
        assertTrue(reason.contains("刚调整过偏好"))
    }

    @Test
    fun testQuestionLimitOneCannotBeRelaxedByFactorOnePointOne() {
        val factor = 1.1
        val limits = PlanConstraints.resolveLimits(
            usableCredit = PlanConstraints.calculateUsableCredit(10, factor),
            preferenceNewWords = 5,
            isBeginner = false,
            questionLimit = 1
        )
        // 核心验收项：即使系数为 1.1，用户的 questionLimit 依然受到严格保持，不得放宽
        assertEquals(1, limits.questionLimit)
    }

    @Test
    fun testReduceToZeroTriggersAbandonOutcome() {
        val basePlan = ProposedPlan("标题", "原因", listOf(
            ProposedItem(1L, "a", "释义", "", "", "due", "", listOf(ProposedStage("WEAK_PRACTICE", "spelling", 1)))
        ), 1)

        val outcome = PlanRevisionEngine.applyOperations(
            basePlan = basePlan,
            operations = listOf(PlanOperation.Drop(1L)),
            immutableWordIds = emptySet(),
            limits = PlanConstraints.resolveLimits(18, 5, false, questionLimit = 0),
            pools = CandidatePools(emptyList(), emptyList(), emptyList(), emptyList())
        )

        // 核心验收项：题目减至 0 显式走 abandon，不伪称完成
        assertTrue(outcome is RevisionEngineOutcome.Abandoned)
    }
}
