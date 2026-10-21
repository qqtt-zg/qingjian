//! 电脑来源上下文里个人释义那一段的解析：只做传输格式的转换。
//!
//! 规则本身在 GlossaryLookup.kt 里（不依赖 org.json），这里只把 JSON 变成 PersonalGloss。
package org.qingjian.speech.learning.glossary

import org.json.JSONArray

/** 读接口返回的 `glosses` 数组。格式不对的行跳过，不整份丢弃。 */
fun JSONArray.toPersonalGlosses(): List<PersonalGloss> {
    val rows = mutableListOf<PersonalGloss>()
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        rows.add(PersonalGloss(
            item.optString("language").trim(),
            item.optString("word").trim(),
            item.optString("pos").trim().ifEmpty { null },
            item.optString("text").trim(),
            item.optString("reading").trim().ifEmpty { null }))
    }
    return rows
}
