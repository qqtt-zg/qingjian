//! 词库与学习词卡共用的笔记编辑框；只写用户确认的内容，不覆盖已有词义。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import org.qingjian.speech.learning.glossary.GlossaryLookup
import org.qingjian.speech.learning.glossary.GlossaryStore

object WordEditor {
    fun show(activity: Activity, word: VocabularyEntry? = null, saved: () -> Unit) {
        val ui = Views(activity)
        val store = LearningStore.get(activity)
        val fields = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val english = ui.input(if (word == null) "输入中文（如「候选」）或英文" else "英文单词或短语", word?.english.orEmpty()).apply { isEnabled = word == null }
        val hint = ui.caption("支持输入中文查英文，或输入英文查中文释义")
        val meaning = ui.input("中文笔记（可留空）", word?.meaning.orEmpty(), true)
        fields.addView(english); fields.addView(hint); fields.addView(meaning)

        fun lookupZhToEn(text: String) = GlossaryLookup(
            GlossaryStore.bundled(activity, "en"),
            GlossaryStore.load(activity, "en")
        ).find(text)

        fun lookupEnToZh(text: String) = GlossaryStore.bundled(activity, "zh")?.find(text)

        if (word == null) {
            english.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val res = WordEditorLookup.resolve(
                        input = s?.toString().orEmpty(),
                        userMeaning = meaning.text.toString(),
                        lookupZhToEn = ::lookupZhToEn,
                        lookupEnToZh = ::lookupEnToZh
                    )
                    hint.text = res.hintText
                    if (res.matchFound && meaning.text.isBlank() && res.meaning.isNotBlank()) {
                        meaning.setText(res.meaning)
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        val dialog = AlertDialog.Builder(activity).setTitle(if(word == null) "添加学习条目" else "编辑中文笔记")
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val inputVal = english.text.toString().trim()
                if (inputVal.isEmpty()) { english.error = "请输入内容"; return@setOnClickListener }

                val resolved = if (word == null) {
                    WordEditorLookup.resolve(
                        input = inputVal,
                        userMeaning = meaning.text.toString(),
                        lookupZhToEn = ::lookupZhToEn,
                        lookupEnToZh = ::lookupEnToZh
                    )
                } else {
                    WordEditorResult(word.english, meaning.text.toString().trim(), word.glossOrigin, "", false, true)
                }

                if (resolved.english.isEmpty()) {
                    english.error = "词典中未收录该中文，需手动指定英文单词"
                    return@setOnClickListener
                }
                if (resolved.english.length > 500) { english.error = "请输入 1～500 字的英文"; return@setOnClickListener }
                if (resolved.meaning.length > 1000) { meaning.error = "中文笔记请控制在 1000 字以内"; return@setOnClickListener }

                val id = word?.id ?: store.addWord(resolved.english, resolved.origin)
                if (word != null || resolved.meaning.isNotBlank()) store.setMeaning(id, resolved.meaning)
                dialog.dismiss(); saved()
            }
        }
        dialog.show()
    }
}

