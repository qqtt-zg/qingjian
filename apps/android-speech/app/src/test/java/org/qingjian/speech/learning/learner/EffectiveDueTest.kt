//! 纯函数衰减计算的单元测试：验证 60 天与 30 天未复测触发的提前到期，以及读时不变性。
package org.qingjian.speech.learning.learner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveDueTest {
    private val now = 1792836000000L // 2026-10-24 10:00:00 UTC
    private val dayMillis = 86400000L

    @Test
    fun testNullNextDueStaysNull() {
        assertNull(EffectiveDue.calculate("solid", null, now - 100 * dayMillis, now))
        assertNull(EffectiveDue.calculate("learning", null, null, now))
    }

    @Test
    fun testSolidWithin60DaysKeepsNextDue() {
        val nextDue = now + 14 * dayMillis
        val lastAt = now - 20 * dayMillis
        val effective = EffectiveDue.calculate("solid", nextDue, lastAt, now)
        assertEquals(nextDue, effective)
    }

    @Test
    fun testSolidOver60DaysDecaysToNotLaterThanToday() {
        // solid 65 天未复测，本来到期日是 10 天后，由于衰减，不晚于今天
        val nextDue = now + 10 * dayMillis
        val lastAt = now - 65 * dayMillis
        val effective = EffectiveDue.calculate("solid", nextDue, lastAt, now)
        assertEquals(now, effective)

        // 若原到期日已经逾期（昨天已到期），衰减保持 minOf(nextDue, now) 即昨天
        val pastDue = now - 2 * dayMillis
        assertEquals(pastDue, EffectiveDue.calculate("solid", pastDue, lastAt, now))
    }

    @Test
    fun testShakyWithin30DaysKeepsNextDue() {
        val nextDue = now + 7 * dayMillis
        val lastAt = now - 15 * dayMillis
        assertEquals(nextDue, EffectiveDue.calculate("shaky", nextDue, lastAt, now))
    }

    @Test
    fun testShakyOver30DaysAdvancesThreeDays() {
        // shaky 35 天未复测，到期日提前 3 天
        val nextDue = now + 7 * dayMillis
        val lastAt = now - 35 * dayMillis
        val effective = EffectiveDue.calculate("shaky", nextDue, lastAt, now)
        assertEquals(nextDue - 3 * dayMillis, effective)
    }

    @Test
    fun testLearningDoesNotDecay() {
        val nextDue = now + 3 * dayMillis
        val lastAt = now - 90 * dayMillis
        assertEquals(nextDue, EffectiveDue.calculate("learning", nextDue, lastAt, now))
    }
}
