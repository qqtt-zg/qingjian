//! 手动覆盖模型：用户手动标记「已掌握」的覆盖声明，具备 7 天复核与答错自动解除机制。
package org.qingjian.speech.learning.learner

data class UserOverride(
    val wordId: Long,
    val dimension: String,
    val overrideType: String = "mastered",
    val createdAt: Long,
    val validUntil: Long = createdAt + 7 * 86400000L, // 最晚 7 天后必须重新验证
    val active: Boolean = true,
    val releaseReason: String? = null,
    val releasedAt: Long? = null
) {
    /** 检查在指定时间点覆盖是否仍然有效 */
    fun isValid(now: Long): Boolean = active && now <= validUntil
}
