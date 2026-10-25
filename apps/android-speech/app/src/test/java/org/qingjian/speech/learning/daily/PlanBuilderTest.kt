//! 纯本机排程算法的单元测试：覆盖 11 到期词截断、单词多维合并、全 solid/零词/单词/2分钟极小预算等全部边界。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBuilderTest {

    private fun candidateWord(
        id: Long,
        english: String = "word$id",
        meaning: String = "词义$id",
        dimensions: List<CandidateDimension> = emptyList(),
        desktopCommitted: Int = 0,
        desktopLastSeenMillis: Long = 0L,
        phoneErrorRate: Double? = null,
        baseScore: Double = 0.0
    ) = CandidateWord(
        wordId = id,
        english = english,
        meaning = meaning,
        glossOrigin = if (desktopCommitted > 0) "desktop-vocab" else "static-table",
        dimensions = dimensions,
        desktopCommitted = desktopCommitted,
        desktopLastSeenMillis = desktopLastSeenMillis,
        phoneErrorRate = phoneErrorRate,
        baseScore = baseScore
    )

    private fun dueDim(dimension: String, overdueDays: Double = 1.0, state: String = "learning") =
        CandidateDimension(dimension, state, 1792836000000L - (overdueDays * 86400000L).toLong(), overdueDays)

    @Test
    fun testElevenDueWordsCappedAtTen() {
        val dueWords = (1L..11L).map { id ->
            candidateWord(id, dimensions = listOf(dueDim("meaning", overdueDays = id.toDouble())))
        }
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 20, factor = 1.0, factorReason = "", usableCredit = 36),
            candidates = CandidatePools(due = dueWords, weak = emptyList(), fresh = emptyList(), consolidate = emptyList()),
            totalWords = 11
        )
        val limits = PlanConstraints.resolveLimits(pack.budget.usableCredit, preferenceNewWords = 5, isBeginner = false)

        val plan = PlanBuilder.build(pack, limits)

        // 核心验收项：11 个到期词严格截断为不超过 10 项
        assertEquals(10, plan.items.size)
        assertTrue(PlanConstraints.validate(plan, limits) is ValidationResult.Success)
    }

    @Test
    fun testMultiDimensionMergeNoDuplicateBudget() {
        // 单个单词多维度逾期 (spelling, listening, meaning)
        val candidate = candidateWord(
            id = 100L,
            english = "candidate",
            dimensions = listOf(
                dueDim("spelling", overdueDays = 5.0, state = "shaky"),
                dueDim("listening", overdueDays = 4.0, state = "learning"),
                dueDim("meaning", overdueDays = 2.0, state = "solid")
            )
        )
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 10, factor = 1.0, factorReason = "", usableCredit = 18),
            candidates = CandidatePools(due = listOf(candidate), weak = emptyList(), fresh = emptyList(), consolidate = emptyList()),
            materials = mapOf(100L to MaterialStatus(100L, true, true, true, true, 4, true)),
            totalWords = 1
        )
        val limits = PlanConstraints.resolveLimits(pack.budget.usableCredit, preferenceNewWords = 5, isBeginner = false)

        val plan = PlanBuilder.build(pack, limits)

        // 核心验收项：同一词多维度合并到同一个 item 中，环节数至多 2 个，不重复扣除到期预算
        assertEquals(1, plan.items.size)
        val item = plan.items[0]
        assertEquals(100L, item.wordId)
        assertTrue(item.stages.size <= 2)
        assertEquals(item.totalCredit, plan.plannedCredit)
        assertTrue(PlanConstraints.validate(plan, limits) is ValidationResult.Success)
    }

    @Test
    fun testZeroWordsFixture() {
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 10, factor = 1.0, factorReason = "", usableCredit = 18),
            candidates = CandidatePools(due = emptyList(), weak = emptyList(), fresh = emptyList(), consolidate = emptyList()),
            totalWords = 0
        )
        val limits = PlanConstraints.resolveLimits(18, 5, false)
        val plan = PlanBuilder.build(pack, limits)

        assertEquals(0, plan.items.size)
        assertTrue(plan.title.contains("暂无"))
    }

    @Test
    fun testOneWordColdStartFixture() {
        val word = candidateWord(1L, "first", "第一")
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 10, factor = 1.0, factorReason = "", usableCredit = 18),
            candidates = CandidatePools(due = emptyList(), weak = emptyList(), fresh = listOf(word), consolidate = emptyList()),
            materials = mapOf(1L to MaterialStatus(1L, true, false, false, false, 0, false)), // 缺例句、缺干扰项
            totalWords = 1
        )
        val limits = PlanConstraints.resolveLimits(18, 5, false)
        val plan = PlanBuilder.build(pack, limits)

        assertEquals(1, plan.items.size)
        // 缺材料时自动降级为盲写练习，不报错
        assertEquals("first", plan.items[0].english)
        assertTrue(plan.items[0].stages.any { it.stageType == "PRACTICE_BASIC" })
        assertTrue(PlanConstraints.validate(plan, limits) is ValidationResult.Success)
    }

    @Test
    fun testAllSolidNoDueFixture() {
        val solidWord = candidateWord(
            1L,
            "solidword",
            dimensions = listOf(CandidateDimension("meaning", "solid", System.currentTimeMillis() + 30 * 86400000L))
        )
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 10, factor = 1.0, factorReason = "", usableCredit = 18),
            candidates = CandidatePools(due = emptyList(), weak = emptyList(), fresh = emptyList(), consolidate = emptyList()),
            totalWords = 1
        )
        val limits = PlanConstraints.resolveLimits(18, 5, false)
        val plan = PlanBuilder.build(pack, limits)

        assertEquals(0, plan.items.size)
        assertTrue(plan.title.contains("掌握"))
    }

    @Test
    fun testTwoMinuteBudgetFixture() {
        val freshWord = candidateWord(1L, "mini", "微型")
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 2, factor = 1.0, factorReason = "", usableCredit = 3), // 2分钟 -> 3 credit
            candidates = CandidatePools(due = emptyList(), weak = emptyList(), fresh = listOf(freshWord), consolidate = emptyList()),
            totalWords = 1
        )
        val limits = PlanConstraints.resolveLimits(3, 5, false)
        val plan = PlanBuilder.build(pack, limits)

        assertEquals(1, plan.items.size)
        assertEquals(3, plan.plannedCredit)
        assertTrue(plan.plannedCredit <= limits.usableCredit)
        assertTrue(PlanConstraints.validate(plan, limits) is ValidationResult.Success)
    }

    @Test
    fun testDeterminismSameSnapshotProducesIdenticalPlan() {
        val words = (1L..5L).map { id ->
            candidateWord(id, "word$id", dimensions = listOf(dueDim("meaning", overdueDays = id.toDouble())))
        }
        val pack = ContextPack(
            today = "2026-10-25",
            epoch = 1L,
            preferencesRevision = 1,
            desktopRevision = 1L,
            modelRevision = 1,
            budget = BudgetSnapshot(minutes = 10, factor = 1.0, factorReason = "", usableCredit = 18),
            candidates = CandidatePools(due = words, weak = emptyList(), fresh = emptyList(), consolidate = emptyList()),
            totalWords = 5
        )
        val limits = PlanConstraints.resolveLimits(18, 5, false)

        val run1 = PlanBuilder.build(pack, limits)
        val run2 = PlanBuilder.build(pack, limits)

        assertEquals("相同输入两次运行必须产出严格一致的计划（确定性）", run1, run2)
    }
}
