//! 学习页共用的原生控件；沿用现有 App 色彩、卡片与按钮资源。
package org.qingjian.speech.learning

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toolbar
import org.qingjian.speech.R

class Views(private val activity: Activity) {
    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun column(): LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun text(value: String, size: Float = 15f, muted: Boolean = false): TextView = TextView(activity).apply {
        text = value; textSize = size
        setTextColor(activity.getColor(if(muted) R.color.text_secondary else R.color.text_primary))
        setPadding(0, dp(5), 0, dp(9))
    }

    fun title(value: String): TextView = text(value, 27f).apply { setTypeface(null, Typeface.BOLD) }

    fun header(label: String): Toolbar = Toolbar(activity).apply {
        id = R.id.pageToolbar
        title = label
        setTitleTextColor(activity.getColor(R.color.text_primary))
        setBackgroundColor(activity.getColor(R.color.screen_background))
        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.homeAsUpIndicator))
        try { navigationIcon = attrs.getDrawable(0) } finally { attrs.recycle() }
        navigationContentDescription = "返回"
        setNavigationOnClickListener { activity.finish() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(56))
    }

    fun page(label: String, content: LinearLayout): LinearLayout = column().apply {
        setBackgroundColor(activity.getColor(R.color.screen_background))
        addView(header(label))
        addView(scroll(content), LinearLayout.LayoutParams(-1, 0, 1f))
        insets(this)
    }

    fun button(label: String, primary: Boolean = false, click: () -> Unit): Button = Button(activity).apply {
        text = label; isAllCaps = false; minHeight = dp(48)
        setTextColor(if(primary) android.graphics.Color.WHITE else activity.getColor(R.color.system_blue))
        setBackgroundResource(if(primary) R.drawable.bg_primary_button else R.drawable.bg_secondary_button)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { click() }
    }

    fun input(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(activity).apply {
        this.hint = hint; setText(value); textSize = 16f
        setTextColor(activity.getColor(R.color.text_primary)); setHintTextColor(activity.getColor(R.color.text_secondary))
        setBackgroundResource(R.drawable.bg_input_field)
        setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(48)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if(multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        setSingleLine(!multiline)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    fun card(parent: LinearLayout, block: (LinearLayout) -> Unit): LinearLayout = column().apply {
        setBackgroundResource(R.drawable.bg_grouped_card); setPadding(dp(16), dp(12), dp(16), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        block(this)
    }

    fun scroll(column: LinearLayout): ScrollView = ScrollView(activity).apply {
        isFillViewport = true
        column.setPadding(dp(18), dp(16), dp(18), dp(24))
        addView(column, ViewGroup.LayoutParams(-1, -2))
        setBackgroundColor(activity.getColor(R.color.screen_background))
    }

    fun insets(root: View) {
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }
}
