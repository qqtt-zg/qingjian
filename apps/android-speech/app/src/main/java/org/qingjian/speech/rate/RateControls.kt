//! 仅在朗读子页显示的语速控件；滑动和预设立即保存，下一条合成读取。
package org.qingjian.speech.rate

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

class RateControls(private val activity: Activity) {
    private val ui = Views(activity)
    private val amount = ui.text("", 15f).apply { id = R.id.tvRateAmount }
    val view = ui.column()
    private val slider = SeekBar(activity).apply {
        id = R.id.sbRate; max = (SpeechRate.MAX - SpeechRate.MIN) / SpeechRate.STEP
        contentDescription = "朗读语速"
    }
    private val presets = mutableListOf<Pair<Int, Button>>()

    init {
        view.setPadding(0, ui.dp(10), 0, ui.dp(12))
        amount.labelFor = slider.id
        view.addView(amount)
        view.addView(slider, LinearLayout.LayoutParams(-1, ui.dp(48)))
        val row = LinearLayout(activity)
        for(percent in listOf(75, 100, 125, 150)) {
            val button = Button(activity).apply {
                id = when(percent) { 100 -> R.id.btnRateNormal; 125 -> R.id.btnRate125; else -> android.view.View.generateViewId() }
                text = if(percent == 100) "正常" else SpeechRate.label(percent)
                isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
                setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8))
                setTextColor(activity.getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener { SpeechRate.save(activity, percent); refresh() }
            }
            presets += percent to button
            row.addView(button, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { marginEnd = ui.dp(3) })
        }
        view.addView(row)
        view.addView(ui.text("0.50×～2.00×，下一条朗读生效", 12f, true))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if(fromUser) { SpeechRate.save(activity, SpeechRate.MIN + value * SpeechRate.STEP); refresh() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        refresh()
    }

    fun refresh() {
        val percent = SpeechRate.percent(activity)
        amount.text = "朗读语速：${SpeechRate.label(percent)}"
        slider.progress = (percent - SpeechRate.MIN) / SpeechRate.STEP
        presets.forEach { (value, button) -> button.isSelected = value == percent }
    }
}
