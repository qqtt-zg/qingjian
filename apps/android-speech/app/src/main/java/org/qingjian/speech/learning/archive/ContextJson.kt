//! 电脑来源响应的 JSON 适配层：只做传输格式到纯逻辑类型的转换。
//!
//! 规则在 ContextParse.kt 里（不依赖 org.json），这里只把 JSON 变成 DesktopContext。
//! 两个接口分开解析：新接口的 `last` 是 ISO 日期、释义叫 `origin_text`，旧接口是
//! Unix 秒、叫 `meaning`；混着猜会写出「2026-10-18 当秒数」这种错。
package org.qingjian.speech.learning.archive

import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.glossary.PersonalGloss

object ContextJson {
    /** 新接口 `GET /api/learning/context`。 */
    fun parseContext(response: JSONObject): DesktopContext {
        val words = mutableListOf<DesktopWord>()
        val rows = response.optJSONArray("words") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val english = row.optString("english")
            if (english.isEmpty()) continue
            words += DesktopWord(
                english = english,
                meaning = row.optString("origin_text"),
                example = row.optString("example"),
                lastSeenMillis = ContextParse.isoDateToMillis(row.optString("last")),
                desktopKnown = row.optBoolean("desktop_known"),
                payload = row.toString(),
            )
        }
        return DesktopContext(
            words = words,
            glosses = response.optJSONArray("glosses").toPersonalGlosses(),
            available = response.optBoolean("available"),
            invalidDateRows = response.optInt("invalid_date_rows", 0),
            glossesTruncated = response.optBoolean("glosses_truncated"),
            truncated = response.optBoolean("truncated"),
            source = DesktopContext.Source.CONTEXT,
        )
    }

    /** 旧接口 `GET /api/learning/words`：没有 glosses，`last` 是 Unix 秒。 */
    fun parseLegacyWords(response: JSONObject): DesktopContext {
        val words = mutableListOf<DesktopWord>()
        val rows = response.optJSONArray("words") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val english = row.optString("english")
            if (english.isEmpty()) continue
            words += DesktopWord(
                english = english,
                meaning = row.optString("meaning"),
                example = row.optString("example"),
                lastSeenMillis = ContextParse.unixSecondsToMillis(row.optLong("last")),
                desktopKnown = row.optBoolean("desktop_known"),
                payload = row.toString(),
            )
        }
        return DesktopContext(
            words = words,
            glosses = emptyList(),
            available = response.optBoolean("available"),
            invalidDateRows = response.optInt("invalid_date_rows", 0),
            glossesTruncated = false,
            truncated = false,
            source = DesktopContext.Source.LEGACY_WORDS,
        )
    }
}

/** 读接口返回的 `glosses` 数组。格式不对的行跳过，不整份丢弃。 */
fun JSONArray?.toPersonalGlosses(): List<PersonalGloss> {
    if (this == null) return emptyList()
    val rows = mutableListOf<PersonalGloss>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        rows += PersonalGloss(
            item.optString("language").trim(),
            item.optString("word").trim(),
            item.optString("pos").trim().ifEmpty { null },
            item.optString("text").trim(),
            item.optString("reading").trim().ifEmpty { null },
        )
    }
    return rows
}
