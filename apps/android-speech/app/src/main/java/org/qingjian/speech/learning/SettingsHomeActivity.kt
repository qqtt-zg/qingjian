//! 全局设置从我的进入；连接在标题原页操作，声音选项在朗读子页。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.qingjian.speech.SpeechSettingsActivity
import org.qingjian.speech.VoiceLibrary
import org.qingjian.speech.R
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.SettingsActivity
import org.qingjian.speech.rate.SpeechRate
import org.qingjian.speech.update.UpdateActivity

class SettingsHomeActivity : Activity() {
    override fun onCreate(state: Bundle?) { super.onCreate(state) }

    override fun onResume() {
        super.onResume()
        val ui = Views(this)
        val preferences = StudyPreferences(this)
        val speech = getSharedPreferences("speech", 0)
        val column = ui.column()
        ui.card(column) { card ->
            card.background = ui.shape(ui.color(R.color.chip_background))
            card.addView(ui.text("按自己的节奏，慢慢来", 20f))
            card.addView(ui.caption("${preferences.level} · 每天 ${preferences.minutes} 分钟"))
        }
        fun open(type: Class<*>) { startActivity(Intent(this, type)) }
        column.addView(ui.caption("学习"))
        column.addView(ui.action("学习偏好", "每天期望 ${preferences.newWords} 个新词", R.drawable.ic_sliders) { open(PreferencesActivity::class.java) })
        val configured = runCatching { Credentials(this).load().validate() }.isSuccess
        column.addView(ui.action("配置与测试 AI", if(configured) "已保存接口配置" else "尚未配置文字 AI 服务", R.drawable.ic_spark) { open(SettingsActivity::class.java) })
        val assessment=runCatching {org.qingjian.speech.learning.assessment.Credentials(this).load().validate()}.isSuccess
        column.addView(ui.action("口语评测",if(assessment)"已保存讯飞评测配置" else "配置讯飞凭据与测试连接",R.drawable.ic_mic) {open(org.qingjian.speech.learning.assessment.SettingsActivity::class.java)})
        column.addView(ui.caption("声音"))
        val names = VoiceLibrary.names(this, speech.getString("pack", "builtin").orEmpty())
        val voice = names.getOrNull(speech.getInt("voice", 1)) ?: names.firstOrNull().orEmpty()
        val gain = (speech.getFloat("gain", 1f).coerceIn(0.5f, 4f) * 100).toInt()
        column.addView(ui.action("朗读设置", "$voice\n音量增强：$gain% · 朗读语速：${SpeechRate.label(SpeechRate.percent(this))}", R.drawable.ic_headphones) { open(SpeechSettingsActivity::class.java) })
        column.addView(ui.caption("应用"))
        column.addView(ui.action("应用更新", getSharedPreferences("app_update", 0).getString("status", "检查版本、下载与安装").orEmpty(), R.drawable.ic_arrow) { open(UpdateActivity::class.java) })
        column.addView(ui.action("数据与关于", "本机记录、版本与开源许可", R.drawable.ic_shield) { open(DataSettingsActivity::class.java) })
        column.addView(ui.caption("点击右上角连接状态可直接停止或重连，离开电脑也可以学习已保存的内容。"))
        val root = ui.page("设置", column)
        setContentView(root)
    }
}
