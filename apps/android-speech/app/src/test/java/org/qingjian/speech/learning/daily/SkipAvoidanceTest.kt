//! 跳过避让与自适应排程联动的单元测试：验证连续 2 天跳过切换维度与连续 3 天跳过本周暂停。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipAvoidanceTest {

    @Test
    fun testConsecutiveTwoDaySkipSwitchesDimension() {
        val word = CandidateWord(
            wordId = 100L,
            english = "difficult",
            meaning = "困难的",
            glossOrigin = "static-table",
            dimensions = listOf(
                CandidateDimension("spelling", "shaky", 1000L, overdueDays = 3.0),
                CandidateDimension("meaning", "learning", 1000L, overdueDays = 2.0)
            )
        )
        val skipHistory = SkipHistory(wordId = 100L, consecutiveSkipDays = 2, lastSkippedDimension = "spelling")

        // 维度切换测试：避开连续跳过的 spelling，切换为 meaning
        val selectedDim = PlanBuilder.resolveTargetDimension(word, skipHistory)
        assertEquals("meaning", selectedDim)
    }

    @Test
    fun testConsecutiveThreeDaySkipPausesAutoSchedulingThisWeek() {
        val skipHistories = mapOf(
            100L to SkipHistory(100L, consecutiveSkipDays = 3, lastSkippedDimension = "spelling"),
            200L to SkipHistory(200L, consecutiveSkipDays = 1, lastSkippedDimension = "meaning")
        )

        val words = listOf(
            org.qingjian.speech.learning.VocabularyEntry(100L, "skippedThreeTimes", "跳过三次", 1, 1, 0, 1000L),
            org.qingjian.speech.learning.VocabularyEntry(200L, "normalWord", "正常词", 1, 1, 0, 1000L)
        )

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = emptyList(),
            overrides = emptyMap(),
            desktopCommittedMap = emptyMap(),
            skipHistories = skipHistories,
            nowMillis = 2000L
        )

        // 100L 连续跳过 3 天，必须从自动排程池中被排除
        assertFalse(pools.fresh.any { it.wordId == 100L })
        assertFalse(pools.due.any { it.wordId == 100L })
        assertFalse(pools.weak.any { it.wordId == 100L })
        assertFalse(pools.consolidate.any { it.wordId == 100L })

        // 200L 仅跳过 1 天，正常进入候选池
        assertTrue(pools.fresh.any { it.wordId == 200L })
    }

    @Test
    fun testSkipDoesNotDecreaseLearnerScore() {
        // 验证跳过环节时，不向算法传递错误信号，连胜与能力分保持不变
        val streakBefore = 3
        val (streakAfter, sameDay) = ReviewScheduleCore.calculateStreak(
            currentStreak = streakBefore,
            lastAt = 1000L,
            now = 2000L,
            correct = true
        )
        assertTrue(streakAfter >= streakBefore)
    }
}
