//! 四个学习入口；连接常驻右上角，系统设置只在我的出现。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import org.qingjian.speech.R

object Navigation {
    val pages = listOf("today" to "学习", "words" to "词库", "ai" to "AI 老师", "me" to "我的")

    fun create(activity: Activity, selected: String, choose: (String) -> Unit): LinearLayout {
        val ui = Views(activity)
        return ui.row().apply {
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(8)); setBackgroundColor(ui.color(R.color.card_background))
            for((index, page) in pages.withIndex()) {
                val (key, label) = page
                val drawable = listOf(R.drawable.ic_study, R.drawable.ic_book, R.drawable.ic_spark, R.drawable.ic_user)[index]
                val button = Button(activity).apply {
                    stateListAnimator = null; elevation = 0f
                    text = label; textSize = 12f; isAllCaps = false; minHeight = ui.dp(60); minWidth = 0; minimumWidth = 0
                    val tint = ui.color(if(selected == key) R.color.system_blue else R.color.text_tertiary)
                    setTextColor(tint); setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(3)); compoundDrawablePadding = ui.dp(3)
                    setCompoundDrawables(null, ui.icon(drawable, tint), null, null)
                    background = ui.shape(ui.color(if(selected == key) R.color.chip_background_active else R.color.card_background), 18)
                    contentDescription = label; isSelected = selected == key
                    setOnClickListener { if(selected != key) choose(key) }
                }
                addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
            }
        }
    }

    fun open(activity: Activity, page: String) {
        val target = if(page == "me") Intent(activity, org.qingjian.speech.learning.archive.ProfileActivity::class.java)
            else if(page == "ai") Intent(activity, TutorActivity::class.java).putExtra("home_tab", true)
            else Intent(activity, LearningActivity::class.java).putExtra("page", page)
        activity.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
