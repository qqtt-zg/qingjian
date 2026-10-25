//! 纯函数复习排程计算：不访问数据库，不产生副作用；
//! 抽离自 ReviewSchedule 并冻结旧间隔表，供旧计划与新学习模型共用计算逻辑。
package org.qingjian.speech.learning.daily

import java.time.Instant
import java.time.ZoneId

object ReviewScheduleCore {
    /** 旧计划冻结的复习间隔表（天）：[1, 1, 3, 7, 14, 30] */
    val LEGACY_INTERVALS = listOf(1, 1, 3, 7, 14, 30)

    const val DAY_MILLIS = 86400000L

    /** 判断两个时间戳在给定本地时区下是否为同一自然日 */
    fun isSameDay(t1: Long, t2: Long, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        if (t1 <= 0L || t2 <= 0L) return false
        val d1 = Instant.ofEpochMilli(t1).atZone(zoneId).toLocalDate()
        val d2 = Instant.ofEpochMilli(t2).atZone(zoneId).toLocalDate()
        return d1 == d2
    }

    /**
     * 计算下一个连胜数及是否命中同日抑制。
     * - 答错：连胜归零；
     * - 答对且同日（上次有成绩）：保持当前连胜，不重复增长；
     * - 答对且跨日（或首次答对）：连胜 +1，最大封顶为 intervals 的最大索引（旧规则封顶 5）。
     *
     * @return Pair(新连胜数, 是否同日)
     */
    fun calculateStreak(
        currentStreak: Int,
        lastAt: Long,
        now: Long,
        correct: Boolean,
        maxStreak: Int = 5,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, Boolean> {
        val sameDay = isSameDay(lastAt, now, zoneId)
        val next = if (!correct) {
            0
        } else if (sameDay && currentStreak > 0) {
            currentStreak
        } else {
            (currentStreak + 1).coerceAtMost(maxStreak)
        }
        return next to sameDay
    }

    /**
     * 根据连胜步数计算下一次复习到期时间戳。
     */
    fun calculateNextDue(
        streak: Int,
        now: Long,
        intervals: List<Int> = LEGACY_INTERVALS
    ): Long {
        val idx = streak.coerceIn(0, intervals.size - 1)
        val days = intervals[idx]
        return now + days * DAY_MILLIS
    }
}
