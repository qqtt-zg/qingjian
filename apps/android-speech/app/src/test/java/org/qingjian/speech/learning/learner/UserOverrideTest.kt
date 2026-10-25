//! 用户手动覆盖模型的单元测试：验证 7 天有效期、覆盖状态判定及错误解除依据。
package org.qingjian.speech.learning.learner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserOverrideTest {
    private val now = 1792836000000L
    private val dayMillis = 86400000L

    @Test
    fun testOverrideValidWithin7Days() {
        val override = UserOverride(
            wordId = 1L,
            dimension = "spelling",
            createdAt = now
        )
        // 5 天后仍有效
        assertTrue(override.isValid(now + 5 * dayMillis))
        // 7 天整依然有效
        assertTrue(override.isValid(now + 7 * dayMillis))
    }

    @Test
    fun testOverrideExpiresAfter7Days() {
        val override = UserOverride(
            wordId = 1L,
            dimension = "spelling",
            createdAt = now
        )
        // 8 天后自动过期
        assertFalse(override.isValid(now + 8 * dayMillis))
    }

    @Test
    fun testReleasedOverrideIsInvalid() {
        val override = UserOverride(
            wordId = 1L,
            dimension = "spelling",
            createdAt = now,
            active = false,
            releaseReason = "incorrect_answer",
            releasedAt = now + 1 * dayMillis
        )
        assertFalse(override.isValid(now + 2 * dayMillis))
    }
}
