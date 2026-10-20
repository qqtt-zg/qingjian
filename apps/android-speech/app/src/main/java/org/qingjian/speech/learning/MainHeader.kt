//! 四个主页共用标题栏；状态与扫码固定靠右，设置只在我的出现。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.widget.LinearLayout
import org.qingjian.speech.R

object MainHeader {
    fun create(activity: Activity, title: String, settings: Boolean = false, menu: (() -> Unit)? = null): LinearLayout {
        val ui = Views(activity)
        fun icon(label: String, drawable: Int, click: () -> Unit) = ui.iconButton(label, drawable, click).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            setCompoundDrawables(ui.icon(drawable).apply { setBounds(0, 0, ui.dp(20), ui.dp(20)) }, null, null, null)
            background = RippleDrawable(ColorStateList.valueOf(0x1839532b), ui.shape(Color.TRANSPARENT, 12), null)
        }
        return ui.row().apply {
            id = R.id.mainHeader
            minimumHeight = ui.dp(60); setPadding(ui.dp(12), 0, ui.dp(8), 0)
            if(menu != null) addView(icon("展开学习对话", R.drawable.ic_menu, menu))
            addView(ui.title(title).apply {
                id = R.id.mainTitle; textSize = 20f; maxLines = 1; setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if(settings) addView(icon("设置", R.drawable.ic_settings) {
                activity.startActivity(Intent(activity, SettingsHomeActivity::class.java))
            }.apply {
                id = R.id.appSettings
            })
            addView(ConnectionBar(activity, compact = true).view)
        }
    }
}
