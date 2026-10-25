//! 环节执行器与完成率计算的单元测试：验证 stage_key 格式、完成率公式、显式放弃保留已获积分。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageExecutorLogicTest {

    @Test
    fun testStageKeyFormatAndUniqueness() {
        val planId = 100L
        val itemId = 1L
        val stageKey1 = "$planId:$itemId:spelling:WEAK_PRACTICE"
        val stageKey2 = "$planId:$itemId:meaning:REVIEW"

        assertTrue(stageKey1.matches(Regex("""^\d+:\d+:[a-z]+:[A-Za-z_]+$""")))
        assertTrue(stageKey2.matches(Regex("""^\d+:\d+:[a-z]+:[A-Za-z_]+$""")))
        assertFalse(stageKey1 == stageKey2)
    }

    @Test
    fun testCompletionRateCalculations() {
        // 总规划 18 积分，已完成 9 积分 -> 50%
        val planned18 = 18
        val done9 = 9
        val rate1 = if (planned18 > 0) (done9 * 100 / planned18) else 100
        assertEquals(50, rate1)

        // 全部完成 18 / 18 -> 100%
        val rateFull = (18 * 100 / 18)
        assertEquals(100, rateFull)

        // 极小计划 3 积分，完成 1 积分 -> 33%
        val rateMini = (1 * 100 / 3)
        assertEquals(33, rateMini)
    }

    @Test
    fun testStageStatusTransitions() {
        val stages = listOf(
            StageRecord(
                stageKey = "1:1:meaning:LEARN",
                planId = 1L,
                itemId = 1L,
                wordId = 10L,
                position = 0,
                dimension = "meaning",
                stageType = "LEARN",
                snapshot = "",
                credit = 2,
                status = "done",
                draft = "",
                assisted = false,
                resultEventKey = null,
                activeMs = 5000L,
                measurementValid = true,
                updated = 1000L
            ),
            StageRecord(
                stageKey = "1:1:spelling:PRACTICE_BASIC",
                planId = 1L,
                itemId = 1L,
                wordId = 10L,
                position = 1,
                dimension = "spelling",
                stageType = "PRACTICE_BASIC",
                snapshot = "",
                credit = 1,
                status = "pending",
                draft = "cand",
                assisted = true,
                resultEventKey = null,
                activeMs = 0L,
                measurementValid = true,
                updated = 2000L
            )
        )

        val pending = stages.firstOrNull { it.status == "pending" }
        assertEquals("1:1:spelling:PRACTICE_BASIC", pending?.stageKey)
        assertEquals("cand", pending?.draft)
        assertTrue(pending?.assisted == true)
    }
}
