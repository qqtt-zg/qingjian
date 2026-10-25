//! 计划修订引擎单元测试：验证原子操作校验、已完成不可变性防护、重排覆盖及约束复核。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRevisionEngineTest {

    private fun sampleItem(wordId: Long, english: String, reason: String = "due", credit: Int = 2) = ProposedItem(
        wordId = wordId,
        english = english,
        meaning = "释义$wordId",
        example = "Example $english",
        translation = "例句翻译",
        reasonCode = reason,
        reasonText = "说明",
        stages = listOf(ProposedStage("WEAK_PRACTICE", "spelling", credit))
    )

    private val emptyPools = CandidatePools(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun testImmutabilityProtectsDoneAndSkippedStages() {
        val basePlan = ProposedPlan("标题", "原因", listOf(
            sampleItem(1L, "doneWord"),
            sampleItem(2L, "skippedWord"),
            sampleItem(3L, "pendingWord")
        ), 6)

        val immutableWordIds = setOf(1L, 2L)
        val limits = PlanConstraints.resolveLimits(18, 5, false)

        // 1. 尝试 drop 已完成词汇 -> 必须拒绝
        val dropDone = PlanOperation.Drop(1L)
        val res1 = PlanRevisionEngine.checkOperation(basePlan, dropDone, immutableWordIds, limits, emptyPools)
        assertTrue(res1 is OperationCheckResult.Rejected)
        assertTrue((res1 as OperationCheckResult.Rejected).reason.contains("已完成或已跳过"))

        // 2. 尝试 swap 已跳过词汇 -> 必须拒绝
        val swapSkipped = PlanOperation.Swap(2L, 4L)
        val res2 = PlanRevisionEngine.checkOperation(basePlan, swapSkipped, immutableWordIds, limits, emptyPools)
        assertTrue(res2 is OperationCheckResult.Rejected)
        assertTrue((res2 as OperationCheckResult.Rejected).reason.contains("已完成或已跳过"))

        // 3. drop 尚未开始的 pending 词汇 -> 允许成功
        val dropPending = PlanOperation.Drop(3L)
        val res3 = PlanRevisionEngine.checkOperation(basePlan, dropPending, immutableWordIds, limits, emptyPools)
        assertTrue(res3 is OperationCheckResult.Accepted)
    }

    @Test
    fun testIllegalWordRejection() {
        val basePlan = ProposedPlan("标题", "原因", listOf(sampleItem(1L, "apple")), 2)
        val limits = PlanConstraints.resolveLimits(18, 5, false)
        val pools = CandidatePools(
            due = listOf(CandidateWord(2L, "banana", "香蕉", "static-table", emptyList())),
            weak = emptyList(), fresh = emptyList(), consolidate = emptyList()
        )

        // 试图添加不在候选池中的词汇 999L
        val addOp = PlanOperation.Add(999L)
        val res = PlanRevisionEngine.checkOperation(basePlan, addOp, emptySet(), limits, pools)
        assertTrue(res is OperationCheckResult.Rejected)
        assertTrue((res as OperationCheckResult.Rejected).reason.contains("不在候选池"))
    }

    @Test
    fun testReorderMustCoverAllRemainingItemsPrecisely() {
        val basePlan = ProposedPlan("标题", "原因", listOf(
            sampleItem(1L, "a"),
            sampleItem(2L, "b"),
            sampleItem(3L, "c")
        ), 6)
        val limits = PlanConstraints.resolveLimits(18, 5, false)

        // 1. 遗漏 3L -> 拒绝
        val missingOp = PlanOperation.Reorder(listOf(2L, 1L))
        val r1 = PlanRevisionEngine.checkOperation(basePlan, missingOp, emptySet(), limits, emptyPools)
        assertTrue(r1 is OperationCheckResult.Rejected)

        // 2. 包含重复 ID -> 拒绝
        val dupOp = PlanOperation.Reorder(listOf(1L, 2L, 2L))
        val r2 = PlanRevisionEngine.checkOperation(basePlan, dupOp, emptySet(), limits, emptyPools)
        assertTrue(r2 is OperationCheckResult.Rejected)

        // 3. 精确覆盖并重排 -> 接受
        val validOp = PlanOperation.Reorder(listOf(3L, 1L, 2L))
        val r3 = PlanRevisionEngine.checkOperation(basePlan, validOp, emptySet(), limits, emptyPools)
        assertTrue(r3 is OperationCheckResult.Accepted)
        assertEquals(listOf(3L, 1L, 2L), (r3 as OperationCheckResult.Accepted).newPlan.items.map { it.wordId })
    }

    @Test
    fun testFullConstraintRecheckPreventsBudgetExceeded() {
        // 可用预算为 3 Credit
        val limits = PlanLimits(usableCredit = 3, dueTarget = 1, dueCap = 4, newWordCap = 2, maxItems = 10)
        val basePlan = ProposedPlan("标题", "原因", listOf(sampleItem(1L, "a", credit = 3)), 3)
        val pools = CandidatePools(
            due = listOf(CandidateWord(2L, "b", "释义", "static-table", emptyList())),
            weak = emptyList(), fresh = emptyList(), consolidate = emptyList()
        )

        // 尝试添加 2L (1 credit)，导致总 Credit 3 + 1 = 4 > 3
        val addOp = PlanOperation.Add(2L)
        val res = PlanRevisionEngine.checkOperation(basePlan, addOp, emptySet(), limits, pools)
        assertTrue(res is OperationCheckResult.Rejected)
        assertTrue((res as OperationCheckResult.Rejected).reason.contains("超过可用预算"))
    }

    @Test
    fun testAdoptionRateCalculation() {
        val basePlan = ProposedPlan("标题", "原因", listOf(
            sampleItem(1L, "a"),
            sampleItem(2L, "b")
        ), 4)
        val limits = PlanConstraints.resolveLimits(18, 5, false)
        val pools = CandidatePools(
            due = listOf(CandidateWord(3L, "c", "释义c", "static-table", emptyList())),
            weak = emptyList(), fresh = emptyList(), consolidate = emptyList()
        )

        // 1 成功 (Add 3L) + 1 失败 (Add 不存在的 999L)
        val outcome = PlanRevisionEngine.applyOperations(
            basePlan = basePlan,
            operations = listOf(PlanOperation.Add(3L), PlanOperation.Add(999L)),
            immutableWordIds = emptySet(),
            limits = limits,
            pools = pools
        )

        assertTrue(outcome is RevisionEngineOutcome.Success)
        val success = outcome as RevisionEngineOutcome.Success
        assertEquals(1, success.acceptedCount)
        assertEquals(1, success.rejectedCount)
        assertEquals(0.5, success.adoptionRate, 0.001)
    }
}
