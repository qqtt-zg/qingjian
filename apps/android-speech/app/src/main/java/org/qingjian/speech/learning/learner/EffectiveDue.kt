//! 纯函数衰减计算：读时动态推算有效到期日，不反复扣减，绝对不回写数据库。
package org.qingjian.speech.learning.learner

object EffectiveDue {
    private const val DAY_MILLIS = 86400000L

    const val SOLID_DECAY_WINDOW_DAYS = 60L
    const val SHAKY_DECAY_WINDOW_DAYS = 30L
    const val SHAKY_ADVANCE_DAYS = 3L

    /**
     * 计算读时有效到期日（effective_due）：
     * - nextDue 为空保持为空；
     * - solid 超过 60 天未验证：effective_due 不晚于当前时间（立即进入到期复习池）；
     * - shaky 超过 30 天未验证：effective_due 将已知到期日提前 3 天；
     * - 衰减不修改原始记录，未知日期仍未知。
     */
    fun calculate(state: String, nextDue: Long?, lastAt: Long?, nowMillis: Long): Long? {
        if (nextDue == null) return null
        return when (state) {
            "solid" -> {
                if (lastAt != null && (nowMillis - lastAt) > SOLID_DECAY_WINDOW_DAYS * DAY_MILLIS) {
                    minOf(nextDue, nowMillis)
                } else {
                    nextDue
                }
            }
            "shaky" -> {
                if (lastAt != null && (nowMillis - lastAt) > SHAKY_DECAY_WINDOW_DAYS * DAY_MILLIS) {
                    nextDue - (SHAKY_ADVANCE_DAYS * DAY_MILLIS)
                } else {
                    nextDue
                }
            }
            else -> nextDue
        }
    }
}
