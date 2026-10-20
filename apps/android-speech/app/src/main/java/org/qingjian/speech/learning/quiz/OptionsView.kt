//! 每个英文选项有独立试听按钮；答案锁定后仍可试听，不触发提交。
package org.qingjian.speech.learning.quiz

import android.app.Activity
import android.widget.LinearLayout
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

class OptionsView(activity: Activity, private val speech: PreviewSpeech) {
    private val ui = Views(activity)

    fun render(parent: LinearLayout, question: Question, answered: Attempt?, enabled: Boolean = true, submit: (String) -> Unit) {
        for(option in question.options) {
            val row = ui.row().apply {
                val color = when {
                    answered?.answer == option && !answered.correct -> R.color.system_red_light
                    answered != null && question.correct(option) -> R.color.chip_background_active
                    else -> R.color.card_background
                }
                background = ui.shape(ui.color(color), 12, true)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
            }
            row.addView(ui.button(option) { speech.stop(); submit(option) }.apply {
                isEnabled = answered == null && enabled
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if(question.englishOptions) row.addView(speech.button("${question.key}:$option", option))
            parent.addView(row)
        }
        if(question.englishOptions) parent.addView(speech.label())
    }
}
