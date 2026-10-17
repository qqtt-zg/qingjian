//! 学习与统一设置页共用的四个底部标签；活动切换不负责启停朗读服务。
package org.qingjian.speech.learning

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import org.qingjian.speech.R

object Navigation {
    val pages = listOf("today" to "今日", "words" to "词汇", "review" to "复习", "settings" to "设置")

    fun create(activity: Activity, selected: String, choose: (String) -> Unit): LinearLayout {
        val ui = Views(activity)
        return LinearLayout(activity).apply {
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            setBackgroundColor(activity.getColor(R.color.card_background))
            for((key, label) in pages) {
                val button = Button(activity).apply {
                    text = label; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
                    minHeight = ui.dp(52); setPadding(ui.dp(3), ui.dp(8), ui.dp(3), ui.dp(8))
                    setTextColor(activity.getColor(if(selected == key) R.color.system_blue else R.color.text_secondary))
                    setBackgroundResource(if(selected == key) R.drawable.bg_chip else android.R.color.transparent)
                    contentDescription = "$label，${if(selected == key) "当前页面" else "切换页面"}"
                    isSelected = selected == key
                    setOnClickListener { if(key != selected) choose(key) }
                }
                addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }
}
