//! 电脑来源的解析与状态文案：不依赖 Android，秒级反馈。
//!
//! 两个接口的差别（`last` 是 ISO 日期还是 Unix 秒、释义字段名、有没有 glosses）
//! 在这里验；读 JSON 的适配层在 ContextJson.kt。
package org.qingjian.speech.learning.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextParseTest {
    @Test
    fun parsesIsoDatesAsUtcMidnight() {
        // 2026-10-18T00:00:00Z
        assertEquals(1792281600000L, ContextParse.isoDateToMillis("2026-10-18"))
    }

    @Test
    fun treatsMissingOrMalformedDatesAsUnknown() {
        // 未知一律记 0：写 1 会变成 1970，写当前时间则是假的上屏时间
        for (text in listOf(null, "", "2026-10-18T00:00:00Z", "2026/10/18", "不是日期", "2026-13-01")) {
            assertEquals("应当把 $text 当未知", 0L, ContextParse.isoDateToMillis(text))
        }
    }

    @Test
    fun convertsLegacyUnixSeconds() {
        val now = 1792281600000L
        assertEquals(1792281600000L, ContextParse.unixSecondsToMillis(1792281600L, now))
    }

    @Test
    fun rejectsImplausibleLegacySeconds() {
        val now = 1792281600000L
        // 0/负数表示未知；明显晚于现在的是坏数据；两者都不能落成 1970 或未来
        assertEquals(0L, ContextParse.unixSecondsToMillis(0L, now))
        assertEquals(0L, ContextParse.unixSecondsToMillis(-5L, now))
        assertEquals(0L, ContextParse.unixSecondsToMillis(now / 1000 + 86401, now))
    }

    @Test
    fun describesSkippedDatesOnlyWhenThereAreAny() {
        assertNull(ContextParse.describeInvalidDates(0))
        assertTrue(ContextParse.describeInvalidDates(3)!!.contains("3"))
    }

    @Test
    fun describesGlossTruncationOnlyWhenTruncated() {
        assertNull(ContextParse.describeGlossTruncation(false))
        assertTrue(ContextParse.describeGlossTruncation(true)!!.contains("释义表"))
    }

    @Test
    fun separatesMissingVocabularyFromEmptyVocabulary() {
        // 「电脑没有生词本」与「生词本是空的」不能混为一谈
        val missing = ContextParse.describeAvailability(false, 0)
        val empty = ContextParse.describeAvailability(true, 0)
        val some = ContextParse.describeAvailability(true, 7)
        assertTrue(missing.contains("尚未找到"))
        assertTrue(empty.contains("还没有词"))
        assertTrue(some.contains("7"))
        assertTrue(missing != empty)
    }
}
