//! 隔离模拟器验证设置目录、连接与声音分流，以及返回后的真实偏好刷新。
package org.qingjian.speech

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import org.qingjian.speech.learning.DataSettingsActivity
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.SettingsHomeActivity
import org.qingjian.speech.rate.SpeechRate

object SettingsProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val ui = ProbeUi(test)
        val prefs = app.getSharedPreferences("speech", 0)
        val oldVoice = prefs.getInt("voice", 1)
        val oldGain = prefs.getFloat("gain", 1f)
        val oldUrl = prefs.getString("url", null)
        val oldRate = SpeechRate.percent(app)
        fun open(from: Activity, label: String, type: Class<out Activity>): Activity {
            val monitor = test.addMonitor(type.name, null, false)
            ui.click(from, label)
            val result = test.waitForMonitorWithTimeout(monitor, 15000) ?: error("没有打开 ${type.simpleName}")
            test.removeMonitor(monitor); ui.await("page focused") { result.hasWindowFocus() }; return result
        }
        val settings = test.startActivitySync(Intent(app, SettingsHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("settings focused") { settings.hasWindowFocus() }
        ui.main {
            check(settings.findViewById<View>(R.id.btnScan) == null)
            check(settings.findViewById<View>(R.id.sbGain) == null)
            check(ui.target(settings.window.decorView, "朗读设置") != null)
        }
        ui.main {
            check(ui.target(settings.window.decorView, "电脑连接") == null)
            check(settings.findViewById<View>(R.id.connectionBar) == null)
        }
        val advanced = open(settings, "朗读设置", SpeechSettingsActivity::class.java)
        ui.main {
            for(id in listOf(R.id.etAddress, R.id.spVoicePicker, R.id.gainPresets, R.id.sbGain, R.id.btnTestSpeech, R.id.sbRate, R.id.tvReceived)) {
                check(advanced.findViewById<View>(id).isShown)
            }
            check(!advanced.findViewById<View>(R.id.btnScan).isShown)
            check(!advanced.findViewById<View>(R.id.btnStop).isShown)
            check(!advanced.findViewById<View>(R.id.connectionStatusRow).isShown)
            advanced.findViewById<EditText>(R.id.etAddress).setText("https://pairing.invalid/#" + "a".repeat(32))
            advanced.findViewById<Button>(R.id.btnConnect).performClick()
            advanced.findViewById<Spinner>(R.id.spVoicePicker).setSelection(3)
            advanced.findViewById<Button>(R.id.btnGain180).performClick()
            advanced.findViewById<Button>(R.id.btnRate125).performClick()
        }
        test.waitForIdleSync()
        check(prefs.getInt("voice", -1) == 3 && prefs.getFloat("gain", 0f) == 1.8f)
        check(SpeechRate.percent(app) == 125)
        check(prefs.getString("url", "") == "https://pairing.invalid/#" + "a".repeat(32))
        ui.main { ToolbarProbe.back(advanced) }
        ui.await("summaries refreshed") { settings.hasWindowFocus() && ui.text(settings.window.decorView).contains("音量增强：180%") }
        ui.main { check(ui.text(settings.window.decorView).contains("朗读语速：1.25×")) }
        val store = LearningStore.get(app)
        store.clearLearning()
        val word = store.addWord("settings probe"); store.setMeaning(word, "设置页测试")
        val data = open(settings, "数据与关于", DataSettingsActivity::class.java)
        ui.main {
            check(ui.button(data.window.decorView, "清空本机学习记录") != null)
            check(ui.button(data.window.decorView, "开源许可与第三方说明") != null)
            ToolbarProbe.back(data)
        }
        ui.await("data return") { settings.hasWindowFocus() }
        ui.main { settings.finish() }
        val home = test.startActivitySync(Intent(app, LearningActivity::class.java).putExtra("page", "review").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("review visible") { home.hasWindowFocus() }
        ui.click(home, "开始复习")
        store.clearLearning()
        ui.main { ToolbarProbe.back(home) }
        val clearSettings = open(home, "设置", SettingsHomeActivity::class.java)
        ui.main { ToolbarProbe.back(clearSettings) }
        ui.await("home return") { home.hasWindowFocus() }
        ui.click(home, "复习一下，记得更牢")
        ui.await("clear review reset") { home.hasWindowFocus() && ui.button(home.window.decorView, "开始复习") != null }
        ui.main { check(!ui.button(home.window.decorView, "开始复习")!!.isEnabled); home.finish() }
        prefs.edit().putInt("voice", oldVoice).putFloat("gain", oldGain).apply {
            if(oldUrl == null) remove("url") else putString("url", oldUrl)
        }.commit()
        SpeechRate.save(app, oldRate)
        return "PASS settings directory, separate connection/speech, no duplicate edit controls, saved-only pairing, refreshed voice/gain/rate, data/about and cleared review state"
    }
}
