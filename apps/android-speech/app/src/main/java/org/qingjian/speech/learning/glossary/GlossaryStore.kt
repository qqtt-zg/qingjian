//! 个人释义表的持久化：电脑攒的 AI 释义要跨次同步留着，不能每次重下。
//!
//! 存在 SharedPreferences 的 JSON 里而不是新建表：它是一张随同步整体替换的小表
//! （上限 2000 条），不需要 SQL 查询；词库页搜索接进来时再考虑换存储。
//! 键按语言分开，与桌面端 `user-glossary-<语言>.tsv` 的命名一致。
package org.qingjian.speech.learning.glossary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object GlossaryStore {
    private const val PREFS = "learning_glossaries"
    private const val LIMIT = 2000
    private const val TEXT_LIMIT = 500

    /** 读回上次存下的个人释义；没有或损坏时返回空表。 */
    fun load(context: Context, language: String): PersonalGlosses {
        val raw = context.getSharedPreferences(PREFS, 0).getString(key(language), null) ?: return PersonalGlosses.of(emptyList(), language, null)
        val rows = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                PersonalGloss(
                    item.optString("language", language),
                    item.optString("word"),
                    item.optString("pos").ifEmpty { null },
                    item.optString("text"),
                    item.optString("reading").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
        return PersonalGlosses.of(rows, language, null)
    }

    /** 整表替换。条数与单条长度按协议上限裁剪，超出的丢掉并让调用方知道。 */
    fun save(context: Context, language: String, glosses: PersonalGlosses): Boolean {
        val rows = JSONArray()
        var dropped = false
        for (word in glosses.words()) {
            if (rows.length() >= LIMIT) { dropped = true; break }
            val sense = glosses.find(word) ?: continue
            val text = sense.text.take(TEXT_LIMIT)
            if (text.isEmpty()) continue
            rows.put(JSONObject()
                .put("language", language)
                .put("word", word)
                .put("pos", sense.partOfSpeech ?: "")
                .put("text", text)
                .put("reading", sense.reading ?: ""))
        }
        context.getSharedPreferences(PREFS, 0).edit().putString(key(language), rows.toString()).apply()
        return dropped
    }

    /**
     * 随包表。现在还没有下载通道，所以通常返回 null；调用方把 null 当作「没有表可比对」，
     * 个人释义全留——这是正确降级，不是错误。
     */
    fun bundled(context: Context, language: String): Glossary? =
        GlossaryFiles.installed(context, language)?.let { Glossary.open(it) }

    private fun key(language: String) = "glossary-$language"
}
