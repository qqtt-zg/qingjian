//! 学习者模型投影引擎的单元测试：验证重放幂等性、四维度状态转移、连胜、同日抑制与最新失败优先。
package org.qingjian.speech.learning.learner

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.qingjian.speech.learning.archive.EventReader

class LearnerModelEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day1 = 1792836000000L // 2026-10-24 10:00:00 UTC
    private val day1Later = day1 + 4 * 3600 * 1000L // 2026-10-24 同日晚些时候
    private val day2 = day1 + 24 * 3600 * 1000L // 2026-10-25 10:00:00 UTC (次日)
    private val day3 = day2 + 24 * 3600 * 1000L // 2026-10-26 10:00:00 UTC (第三日)

    @Test
    fun testReplayIdempotency() {
        val rawEvents = listOf(
            EventFixtures.spellingEvent(key = "sp-1", time = day1, correct = true, hint = 0),
            EventFixtures.choiceEvent(key = "ch-1", time = day1Later, correct = true),
            EventFixtures.oralWordEvent(key = "or-1", time = day2, score = 88.0)
        )
        val normalized = EventReader.normalizeRawEvents(rawEvents, zone)

        // 第一次重放
        val run1 = LearnerModelEngine.project(normalized)
        // 第二次重放
        val run2 = LearnerModelEngine.project(normalized)

        assertEquals("重放结果必须严格一致（幂等）", run1, run2)
    }

    @Test
    fun testAssistedDoesNotCountAsIndependent() {
        // 提示后答对盲写
        val raw = listOf(
            EventFixtures.spellingEvent(key = "sp-1", time = day1, correct = true, hint = 1)
        )
        val normalized = EventReader.normalizeRawEvents(raw, zone)
        val result = LearnerModelEngine.project(normalized)

        val entry = checkNotNull(result[100L to "spelling"])
        assertEquals(1, entry.attempts)
        assertEquals(1, entry.correct)
        assertEquals(1, entry.assisted)
        assertEquals("辅助作答不增加独立作答数", 0, entry.independentAttempts)
        assertEquals(0, entry.independentCorrect)
        assertEquals(0, entry.streak)
        assertEquals("shaky", entry.state)
    }

    @Test
    fun testSameDaySuppressionHoldsStreak() {
        // 同一日内多次独立答对
        val raw = listOf(
            EventFixtures.spellingEvent(key = "sp-1", time = day1, correct = true, hint = 0),
            EventFixtures.spellingEvent(key = "sp-2", time = day1Later, correct = true, hint = 0)
        )
        val normalized = EventReader.normalizeRawEvents(raw, zone)
        val result = LearnerModelEngine.project(normalized)

        val entry = checkNotNull(result[100L to "spelling"])
        assertEquals(2, entry.attempts)
        assertEquals(2, entry.independentAttempts)
        assertEquals(2, entry.independentCorrect)
        // 同日抑制：连胜仅增长 1
        assertEquals(1, entry.streak)
        assertEquals("未跨两日保持 learning", "learning", entry.state)
    }

    @Test
    fun testCrossDaySuccessReachesSolid() {
        val raw = listOf(
            EventFixtures.spellingEvent(key = "sp-1", time = day1, correct = true, hint = 0),
            EventFixtures.spellingEvent(key = "sp-2", time = day2, correct = true, hint = 0)
        )
        val normalized = EventReader.normalizeRawEvents(raw, zone)
        val result = LearnerModelEngine.project(normalized)

        val entry = checkNotNull(result[100L to "spelling"])
        assertEquals(2, entry.streak)
        assertEquals("连续两个不同成功日跃迁为 solid", "solid", entry.state)
    }

    @Test
    fun testIncorrectResetsStreakAndLearningState() {
        val raw = listOf(
            EventFixtures.spellingEvent(key = "sp-1", time = day1, correct = true, hint = 0),
            EventFixtures.spellingEvent(key = "sp-2", time = day2, correct = true, hint = 0),
            // 第三天答错
            EventFixtures.spellingEvent(key = "sp-3", time = day3, correct = false, hint = 0)
        )
        val normalized = EventReader.normalizeRawEvents(raw, zone)
        val result = LearnerModelEngine.project(normalized)

        val entry = checkNotNull(result[100L to "spelling"])
        assertEquals(3, entry.attempts)
        assertEquals(2, entry.correct)
        assertEquals(0, entry.streak)
        assertEquals("答错立即打回 learning", "learning", entry.state)
    }

    @Test
    fun testMeaningThreeAttemptRule() {
        // 独立作答不足 3 次为 learning
        val raw2 = listOf(
            EventFixtures.choiceEvent(key = "ch-1", time = day1, correct = true),
            EventFixtures.choiceEvent(key = "ch-2", time = day2, correct = true)
        )
        val res2 = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw2, zone))
        assertEquals("未满3次为 learning", "learning", res2[100L to "meaning"]!!.state)

        // 达到 3 次，且全对跨两日 -> solid
        val raw3Solid = listOf(
            EventFixtures.choiceEvent(key = "ch-1", time = day1, correct = true),
            EventFixtures.choiceEvent(key = "ch-2", time = day2, correct = true),
            EventFixtures.choiceEvent(key = "ch-3", time = day3, correct = true)
        )
        val res3Solid = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw3Solid, zone))
        assertEquals("达到3次正确率>=0.8且连胜>=2为 solid", "solid", res3Solid[100L to "meaning"]!!.state)

        // 达到 3 次，但 2 错 1 对 (正确率 33% < 0.6) -> shaky
        val raw3Shaky = listOf(
            EventFixtures.choiceEvent(key = "ch-1", time = day1, correct = false),
            EventFixtures.choiceEvent(key = "ch-2", time = day2, correct = false),
            EventFixtures.choiceEvent(key = "ch-3", time = day3, correct = true)
        )
        val res3Shaky = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw3Shaky, zone))
        assertEquals("正确率低于0.6为 shaky", "shaky", res3Shaky[100L to "meaning"]!!.state)
    }

    @Test
    fun testPronunciationScoresAndThresholds() {
        // < 60 分 -> learning
        val raw1 = listOf(EventFixtures.oralWordEvent(key = "o1", score = 55.0, time = day1))
        val res1 = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw1, zone))
        assertEquals("learning", res1[100L to "pronunciation"]!!.state)

        // 60..79 分 -> shaky
        val raw2 = listOf(EventFixtures.oralWordEvent(key = "o2", score = 75.0, time = day1))
        val res2 = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw2, zone))
        assertEquals("shaky", res2[100L to "pronunciation"]!!.state)

        // 跨两日 >= 80 分 -> solid
        val raw3 = listOf(
            EventFixtures.oralWordEvent(key = "o3", score = 85.0, time = day1),
            EventFixtures.oralWordEvent(key = "o4", score = 90.0, time = day2)
        )
        val res3 = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw3, zone))
        assertEquals(2, res3[100L to "pronunciation"]!!.streak)
        assertEquals("solid", res3[100L to "pronunciation"]!!.state)
    }

    @Test
    fun testExposureAndSelfReviewDoNotAdvanceAttemptsOrStreak() {
        val raw = listOf(
            EventFixtures.exposureEvent(key = "exp-1", time = day1),
            EventFixtures.selfReviewEvent(key = "rev-1", remembered = true, time = day2)
        )
        val res = LearnerModelEngine.project(EventReader.normalizeRawEvents(raw, zone))
        val entry = res[100L to "meaning"]
        assertNotNull(entry)
        assertEquals("曝光和自评不计入有效作答 attempts", 0, entry!!.attempts)
        assertEquals(0, entry.streak)
        assertEquals("unknown", entry.state)
        assertNull(entry.nextDue)
    }
}
