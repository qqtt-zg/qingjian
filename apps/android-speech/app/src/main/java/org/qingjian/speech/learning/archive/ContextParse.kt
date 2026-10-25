//! 电脑来源上下文的解析规则：新接口与旧接口的词条形状不同，统一成一种。
//!
//! 纯逻辑放这里，才能被 JVM 单元测试覆盖；读 JSON 的适配层在 ContextJson.kt。
//! 两个接口的差别只有三处：`last` 是 ISO 日期还是 Unix 秒、释义字段叫 `origin_text`
//! 还是 `meaning`、有没有 `glosses`。
package org.qingjian.speech.learning.archive

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import org.qingjian.speech.learning.glossary.PersonalGloss

data class DesktopAppUsage(
    val app: String,
    val commitWithGloss: Int,
    val glossTyped: Int
)

/** 一条电脑来源词条，两个接口都归到这个形状。`payload` 是原始 JSON，整条存进 desktop_sources。 */
class DesktopWord(
    val english: String,
    val meaning: String,
    val example: String,
    val lastSeenMillis: Long,
    val desktopKnown: Boolean,
    val payload: String,
    val apps: List<DesktopAppUsage>? = null
)

/**
 * 电脑来源的一次同步结果。
 *
 * `source` 记录这次实际用了哪个接口，状态文案要据此说明「旧版电脑工具」还是「新版」。
 */
class DesktopContext(
    val words: List<DesktopWord>,
    val glosses: List<PersonalGloss>,
    val available: Boolean,
    val invalidDateRows: Int,
    val glossesTruncated: Boolean,
    val truncated: Boolean,
    val source: Source,
    val appsStatus: String = "not_collected"
) {
    enum class Source { CONTEXT, LEGACY_WORDS }
}

/** 解析与判定规则，不碰网络与文件。 */
object ContextParse {
    /** 把 ISO 日历日期转成 UTC 午夜的毫秒数；未知或非法返回 0（不是 1970，也不是「现在」）。 */
    fun isoDateToMillis(text: String?): Long {
        val value = text?.trim().orEmpty()
        if (value.length != 10) return 0
        return try {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (error: DateTimeParseException) {
            0
        }
    }

    /**
     * 旧接口的 `last` 是 UTC 午夜的 Unix 秒数。
     *
     * 只接受落在合理区间内的值：<=0 表示未知，明显晚于现在的是坏数据。写 1 会在
     * 「今日接触」这类筛选里变成 1970，写当前时间则是假的上屏时间，两者都不能要。
     */
    fun unixSecondsToMillis(seconds: Long, nowMillis: Long = System.currentTimeMillis()): Long {
        if (seconds <= 0) return 0
        if (seconds > nowMillis / 1000 + 86400) return 0
        return seconds * 1000
    }

    /** 状态文案里要不要说明「有坏日期被跳过」。 */
    fun describeInvalidDates(count: Int): String? =
        if (count > 0) "另有 $count 词日期异常已跳过" else null

    /** 状态文案里要不要说明个人释义表被截断。 */
    fun describeGlossTruncation(truncated: Boolean): String? =
        if (truncated) "个人释义表超出上限，只合并了前一部分" else null

    /**
     * 电脑没有生词本文件，与「生词本是空的」是两件事：前者要提示用户去电脑上确认，
     * 后者只是还没遇到词，不该让用户以为连接坏了。
     */
    fun describeAvailability(available: Boolean, wordCount: Int): String = when {
        !available -> "学习档案已检查备份；电脑尚未找到生词本文件"
        wordCount == 0 -> "同步完成 · 电脑生词本里还没有词"
        else -> "同步完成 · 电脑来源 $wordCount 词"
    }
}
