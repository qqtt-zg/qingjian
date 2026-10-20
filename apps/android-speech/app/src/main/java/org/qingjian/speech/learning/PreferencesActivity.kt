//! 保存入门学习方向；偏好影响 AI 教学上下文，不冒充每日调度或学习成绩。
package org.qingjian.speech.learning

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast

class PreferencesActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = Views(this)
        val preferences = StudyPreferences(this)
        val column = ui.column()
        column.addView(ui.text("学习方向先简单一点，之后随时可以调整。", muted = true))
        fun picker(label: String, values: List<String>, selected: Int): Spinner {
            column.addView(ui.caption(label))
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@PreferencesActivity, android.R.layout.simple_spinner_dropdown_item, values)
                minimumHeight = ui.dp(52); setSelection(selected)
                contentDescription = label; column.addView(this)
            }
        }
        val level = picker("当前基础", StudyPreferences.levels, StudyPreferences.levels.indexOf(preferences.level))
        val purpose = picker("主要用途", StudyPreferences.purposes, StudyPreferences.purposes.indexOf(preferences.purpose))
        val quota = picker("每天期望的新词", StudyPreferences.quotas.map { "$it 个" }, StudyPreferences.quotas.indexOf(preferences.newWords))
        val time = picker("每天学习时间", StudyPreferences.times.map { "$it 分钟" }, StudyPreferences.times.indexOf(preferences.minutes))
        column.addView(ui.button("保存学习偏好", true) {
            preferences.save(level.selectedItemPosition, purpose.selectedItemPosition,
                StudyPreferences.quotas[quota.selectedItemPosition], StudyPreferences.times[time.selectedItemPosition])
            Toast.makeText(this, "学习偏好已保存，下次提问时生效", Toast.LENGTH_SHORT).show(); finish()
        })
        column.addView(ui.caption("这些是学习目标，不会自动发送请求、安排后台任务或改变成绩。"))
        setContentView(ui.page("学习偏好", column))
    }
}
