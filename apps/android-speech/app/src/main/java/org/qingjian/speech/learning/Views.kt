//! 青简原生界面共用的色彩、排版、图标与卡片；子页保留系统返回语义。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
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
    fun color(id: Int) = activity.getColor(id)
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun shape(fill: Int, radius: Int = 16, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if(stroke) setStroke(dp(1), color(R.color.card_stroke))
    }

    fun text(value: String, size: Float = 15f, muted: Boolean = false): TextView = TextView(activity).apply {
        text = value; textSize = size
        setTextColor(color(if(muted) R.color.text_secondary else R.color.text_primary))
        setPadding(0, dp(5), 0, dp(9)); setLineSpacing(dp(2).toFloat(), 1.1f)
    }

    fun title(value: String) = text(value, 25f).apply { setTypeface(null, Typeface.BOLD) }
    fun word(value: String) = text(value, 42f).apply { typeface = Typeface.create("serif", Typeface.NORMAL) }
    fun caption(value: String) = text(value, 12f, true)

    fun header(label: String): Toolbar = Toolbar(activity).apply {
        id = R.id.pageToolbar; title = label
        setTitleTextColor(color(R.color.text_primary)); setBackgroundColor(color(R.color.screen_background))
        val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.homeAsUpIndicator))
        try { navigationIcon = attrs.getDrawable(0) } finally { attrs.recycle() }
        navigationContentDescription = "返回"; setNavigationOnClickListener { activity.finish() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(56))
    }

    fun appHeader(label: String, subtitle: String = "", settings: (() -> Unit)? = null): LinearLayout = row().apply {
        setPadding(dp(20), dp(8), dp(14), dp(8))
        val copy = column()
        copy.addView(title(label).apply { textSize = 23f; setPadding(0, 0, 0, 0) })
        if(subtitle.isNotEmpty()) copy.addView(caption(subtitle).apply { setPadding(0, dp(2), 0, 0) })
        addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        if(settings != null) addView(iconButton("设置", R.drawable.ic_settings, settings))
    }

    fun page(label: String, content: LinearLayout): LinearLayout = column().apply {
        setBackgroundColor(color(R.color.screen_background)); addView(header(label))
        addView(scroll(content), LinearLayout.LayoutParams(-1, 0, 1f)); insets(this)
    }

    fun button(label: String, primary: Boolean = false, click: () -> Unit): Button = Button(activity).apply {
        stateListAnimator = null; elevation = 0f
        text = label; textSize = 15f; isAllCaps = false; minHeight = dp(48)
        setTextColor(if(primary) android.graphics.Color.WHITE else color(R.color.system_blue))
        setBackgroundResource(if(primary) R.drawable.bg_primary_button else R.drawable.bg_secondary_button)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
        setOnClickListener { click() }
    }

    fun iconButton(label: String, drawable: Int, click: () -> Unit): Button = Button(activity).apply {
        stateListAnimator = null; elevation = 0f
        contentDescription = label; text = ""; minWidth = 0; minimumWidth = 0; minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        background = RippleDrawable(ColorStateList.valueOf(0x1839532b), shape(color(R.color.chip_background), 24), null)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setCompoundDrawablesWithIntrinsicBounds(icon(drawable), null, null, null)
        setOnClickListener { click() }
    }

    fun icon(drawable: Int, tint: Int = color(R.color.system_blue)) = activity.getDrawable(drawable)!!.mutate().apply {
        setTint(tint); setBounds(0, 0, dp(22), dp(22))
    }

    fun action(label: String, detail: String, drawable: Int, click: () -> Unit): LinearLayout = row().apply {
        setPadding(dp(10), dp(8), dp(8), dp(8)); minimumHeight = dp(70)
        background = RippleDrawable(ColorStateList.valueOf(0x1839532b), shape(color(R.color.card_background), 14), null)
        val image = android.widget.ImageView(activity).apply {
            setImageDrawable(icon(drawable)); setPadding(dp(10), dp(10), dp(10), dp(10))
            background = shape(color(R.color.chip_background), 12); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(image, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(text(label, 15f).apply { setPadding(0, 0, 0, 0); setTypeface(null, Typeface.BOLD) })
            if(detail.isNotEmpty()) addView(caption(detail).apply { setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(android.widget.ImageView(activity).apply { setImageDrawable(icon(R.drawable.ic_chevron)) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        contentDescription = label; isFocusable = true; isClickable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(5) }
    }

    fun wordRow(english: String, meaning: String, click: () -> Unit): LinearLayout = row().apply {
        setPadding(dp(2), dp(10), dp(2), dp(10)); minimumHeight = dp(66)
        background = RippleDrawable(ColorStateList.valueOf(0x1839532b), null, shape(android.graphics.Color.WHITE, 10))
        addView(column().apply {
            addView(word(english).apply { textSize = 22f; setPadding(0, 0, 0, 0) })
            addView(caption(meaning).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(android.widget.ImageView(activity).apply { setImageDrawable(icon(R.drawable.ic_chevron)) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        contentDescription = "学习这个词：$english"; isFocusable = true; setOnClickListener { click() }
    }

    fun menuRow(label:String,detail:String,drawable:Int,click:()->Unit):LinearLayout=row().apply {
        minimumHeight=dp(70);setPadding(0,dp(11),0,dp(11))
        background=RippleDrawable(ColorStateList.valueOf(0x1839532b),null,shape(android.graphics.Color.WHITE,8))
        addView(android.widget.ImageView(activity).apply {setImageDrawable(icon(drawable));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(20),dp(20)).apply {marginEnd=dp(12)})
        addView(column().apply {addView(text(label,14f).apply {setPadding(0,0,0,0)});if(detail.isNotBlank())addView(caption(detail).apply {textSize=11f;setPadding(0,dp(4),0,0)})},LinearLayout.LayoutParams(0,-2,1f))
        addView(android.widget.ImageView(activity).apply {setImageDrawable(icon(R.drawable.ic_chevron))},LinearLayout.LayoutParams(dp(16),dp(16)))
        contentDescription=label;isFocusable=true;setOnClickListener {click()}
    }

    fun input(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(activity).apply {
        this.hint = hint; setText(value); textSize = 16f
        setTextColor(color(R.color.text_primary)); setHintTextColor(color(R.color.text_secondary))
        setBackgroundResource(R.drawable.bg_input_field)
        setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(48)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or if(multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
        setSingleLine(!multiline)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    fun card(parent: LinearLayout, block: (LinearLayout) -> Unit): LinearLayout = column().apply {
        setBackgroundResource(R.drawable.bg_grouped_card); setPadding(dp(17), dp(14), dp(17), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }); block(this)
    }

    fun fold(parent: LinearLayout, label: String, block: (LinearLayout) -> Unit) {
        val content = column().apply { visibility = View.GONE }
        parent.addView(button(label) { content.visibility = if(content.visibility == View.VISIBLE) View.GONE else View.VISIBLE }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT); gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7)); setCompoundDrawables(null, null, icon(R.drawable.ic_chevron), null)
        })
        divider(parent)
        block(content); parent.addView(content)
    }

    fun divider(parent: LinearLayout) { parent.addView(View(activity).apply { setBackgroundColor(color(R.color.divider_color)) }, LinearLayout.LayoutParams(-1, dp(1))) }

    fun scroll(column: LinearLayout): ScrollView = ScrollView(activity).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        column.setPadding(dp(20), dp(12), dp(20), dp(24))
        addView(column, ViewGroup.LayoutParams(-1, -2)); setBackgroundColor(color(R.color.screen_background))
    }

    fun insets(root: View) {
        root.setBackgroundColor(color(R.color.screen_background))
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
    }
}
