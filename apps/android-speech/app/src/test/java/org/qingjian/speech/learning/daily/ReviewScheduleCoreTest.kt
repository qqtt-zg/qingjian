//! 纯函数复习排程的单元测试：验证跨日连胜增长、同日抑制、答错归零与到期时间计算。
package org.qingjian.speech.learning.daily

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewScheduleCoreTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // 2026-10-24 10:00:00 UTC = 2026-10-24 18:00:00 CST
    private val day1Morning = 1792836000000L
    // 2026-10-24 14:00:00 UTC = 2026-10-24 22:00:00 CST (同日)
    private val day1Evening = day1Morning + 4 * 3600 * 1000L
    // 2026-10-25 10:00:00 UTC (次日)
    private val day2Morning = day1Morning + 24 * 3600 * 1000L
    // 2026-10-26 10:00:00 UTC (第三日)
    private val day3Morning = day2Morning + 24 * 3600 * 1000L

    @Test
    fun testSameDayDetection() {
        assertTrue(ReviewScheduleCore.isSameDay(day1Morning, day1Evening, zone))
        assertFalse(ReviewScheduleCore.isSameDay(day1Morning, day2Morning, zone))
        assertFalse(ReviewScheduleCore.isSameDay(0L, day1Morning, zone))
    }

    @Test
    fun testFirstSuccessStartsAtStreakOne() {
        val (streak, sameDay) = ReviewScheduleCore.calculateStreak(
            currentStreak = 0,
            lastAt = 0L,
            now = day1Morning,
            correct = true,
            zoneId = zone
        )
        assertEquals(1, streak)
        assertFalse(sameDay)
    }

    @Test
    fun testSameDaySuppressionHoldsStreak() {
        // 同日多次独立答对，保持连胜不变
        val (streak1, sameDay1) = ReviewScheduleCore.calculateStreak(
            currentStreak = 1,
            lastAt = day1Morning,
            now = day1Evening,
            correct = true,
            zoneId = zone
        )
        assertEquals(1, streak1)
        assertTrue(sameDay1)

        val (streak2, sameDay2) = ReviewScheduleCore.calculateStreak(
            currentStreak = 3,
            lastAt = day1Morning,
            now = day1Evening,
            correct = true,
            zoneId = zone
        )
        assertEquals(3, streak2)
        assertTrue(sameDay2)
    }

    @Test
    fun testCrossDaySuccessIncrementsStreak() {
        // 次日作答正确，连胜递增
        val (streak, sameDay) = ReviewScheduleCore.calculateStreak(
            currentStreak = 1,
            lastAt = day1Morning,
            now = day2Morning,
            correct = true,
            zoneId = zone
        )
        assertEquals(2, streak)
        assertFalse(sameDay)

        // 第三日再答对，连胜递增至 3
        val (streak3, _) = ReviewScheduleCore.calculateStreak(
            currentStreak = streak,
            lastAt = day2Morning,
            now = day3Morning,
            correct = true,
            zoneId = zone
        )
        assertEquals(3, streak3)
    }

    @Test
    fun testIncorrectResetsStreakToZero() {
        // 任何答错立即重置为 0
        val (streak1, _) = ReviewScheduleCore.calculateStreak(
            currentStreak = 4,
            lastAt = day1Morning,
            now = day2Morning,
            correct = false,
            zoneId = zone
        )
        assertEquals(0, streak1)

        // 同日答错也归零
        val (streak2, _) = ReviewScheduleCore.calculateStreak(
            currentStreak = 2,
            lastAt = day1Morning,
            now = day1Evening,
            correct = false,
            zoneId = zone
        )
        assertEquals(0, streak2)
    }

    @Test
    fun testNextDueFollowsLegacyIntervals() {
        val now = day1Morning
        // 间隔表 [1, 1, 3, 7, 14, 30]
        assertEquals(now + 1 * 86400000L, ReviewScheduleCore.calculateNextDue(0, now))
        assertEquals(now + 1 * 86400000L, ReviewScheduleCore.calculateNextDue(1, now))
        assertEquals(now + 3 * 86400000L, ReviewScheduleCore.calculateNextDue(2, now))
        assertEquals(now + 7 * 86400000L, ReviewScheduleCore.calculateNextDue(3, now))
        assertEquals(now + 14 * 86400000L, ReviewScheduleCore.calculateNextDue(4, now))
        assertEquals(now + 30 * 86400000L, ReviewScheduleCore.calculateNextDue(5, now))
        // 超过上限封顶在 30 天
        assertEquals(now + 30 * 86400000L, ReviewScheduleCore.calculateNextDue(10, now))
    }
}
