//! 本机学习偏好；作为明确的数据上下文传给 AI，不赋予模型修改配置的权限。
package org.qingjian.speech.learning

import android.content.Context
import org.json.JSONObject

class StudyPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("learning_preferences", Context.MODE_PRIVATE)

    val level get() = levels[prefs.getInt("level", 0).coerceIn(levels.indices)]
    val purpose get() = purposes[prefs.getInt("purpose", 0).coerceIn(purposes.indices)]
    val newWords get() = prefs.getInt("new_words", 5).takeIf { it in quotas } ?: 5
    val minutes get() = prefs.getInt("minutes", 10).takeIf { it in times } ?: 10
    val revision get() = prefs.getInt("preferences_revision", 0)
    var lastWord: Long
        get() = prefs.getLong("last_word", 0)
        set(value) { prefs.edit().putLong("last_word", value).apply() }

    fun save(level: Int, purpose: Int, count: Int, time: Int) {
        require(level in levels.indices && purpose in purposes.indices && count in quotas && time in times)
        val oldLevel = prefs.getInt("level", 0)
        val oldPurpose = prefs.getInt("purpose", 0)
        val oldCount = prefs.getInt("new_words", 5)
        val oldTime = prefs.getInt("minutes", 10)
        val changed = (level != oldLevel || purpose != oldPurpose || count != oldCount || time != oldTime)
        val nextRev = if (changed) revision + 1 else revision
        prefs.edit()
            .putInt("level", level)
            .putInt("purpose", purpose)
            .putInt("new_words", count)
            .putInt("minutes", time)
            .putInt("preferences_revision", nextRev)
            .apply()
    }

    fun context(): JSONObject = JSONObject().put("基础", level).put("学习用途", purpose)
        .put("期望每天新词", newWords).put("期望每天分钟", minutes)

    companion object {
        val levels = listOf("基础较弱 · 从常用词开始", "能读简单句 · 增加表达")
        val purposes = listOf("日常生活与工作", "工作中的英语表达", "日常交流")
        val quotas = listOf(3, 5, 8)
        val times = listOf(10, 15, 20)
    }
}
