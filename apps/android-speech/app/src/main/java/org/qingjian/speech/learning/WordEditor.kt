//! 词库与学习词卡共用的笔记编辑框；只写用户确认的内容，不覆盖已有词义。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog

object WordEditor {
    fun show(activity: Activity, word: VocabularyEntry? = null, saved: () -> Unit) {
        val ui = Views(activity)
        val store = LearningStore.get(activity)
        val fields = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val english = ui.input("英文单词或短语", word?.english.orEmpty()).apply { isEnabled = word == null }
        val meaning = ui.input("中文笔记（可留空）", word?.meaning.orEmpty(), true)
        fields.addView(english); fields.addView(meaning)
        val dialog = AlertDialog.Builder(activity).setTitle(if(word == null) "添加学习条目" else "编辑中文笔记")
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = english.text.toString().trim()
                if(value.isEmpty() || value.length > 500) { english.error = "请输入 1～500 字的英文"; return@setOnClickListener }
                if(meaning.length() > 1000) { meaning.error = "中文笔记请控制在 1000 字以内"; return@setOnClickListener }
                val id = word?.id ?: store.addWord(value)
                if(word != null || meaning.text.isNotBlank()) store.setMeaning(id, meaning.text.toString())
                dialog.dismiss(); saved()
            }
        }
        dialog.show()
    }
}
