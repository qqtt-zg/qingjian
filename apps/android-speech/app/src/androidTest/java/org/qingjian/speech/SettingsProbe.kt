//! 隔离模拟器验证低频设置分流、返回后的声音偏好刷新与学习记录清空。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import org.qingjian.speech.learning.DataSettingsActivity
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.rate.SpeechRate

object SettingsProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val prefs = app.getSharedPreferences("speech", 0)
        val oldVoice = prefs.getInt("voice", 1)
        val oldGain = prefs.getFloat("gain", 1f)
        val oldUrl = prefs.getString("url", null)
        val oldRate = SpeechRate.percent(app)
        val speech = test.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        test.waitForIdleSync()
        awaitFocus(test, speech)
        test.runOnMainSync {
            check(speech.findViewById<View>(R.id.btnScan).isShown)
            check(!speech.findViewById<View>(R.id.sbGain).isShown)
            check(speech.findViewById<View>(R.id.btnConnect).isShown)
            check(speech.findViewById<View>(R.id.btnStop).isShown)
            check(!speech.findViewById<View>(R.id.btnTestSpeech).isShown)
            check(!speech.findViewById<View>(R.id.etAddress).isShown)
            check(!speech.findViewById<View>(R.id.spVoicePicker).isShown)
            check(!speech.findViewById<View>(R.id.tvReceived).isShown)
            check(!speech.findViewById<View>(R.id.rowBattery).isShown)
            check(speech.findViewById<View>(R.id.sbRate) == null)
        }
        val monitor = test.addMonitor(SpeechSettingsActivity::class.java.name, null, false)
        test.runOnMainSync { button(speech.window.decorView, "朗读设置").performClick() }
        val advanced = test.waitForMonitorWithTimeout(monitor, 10000) as? SpeechSettingsActivity ?: error("没有打开更多设置")
        test.removeMonitor(monitor); test.waitForIdleSync()
        test.runOnMainSync {
            check(advanced.findViewById<View>(R.id.etAddress).isShown)
            check(advanced.findViewById<View>(R.id.spVoicePicker).isShown)
            check(advanced.findViewById<View>(R.id.gainPresets).isShown)
            check(advanced.findViewById<View>(R.id.sbGain).isShown)
            check(advanced.findViewById<View>(R.id.btnTestSpeech).isShown)
            check(advanced.findViewById<View>(R.id.sbRate).isShown)
            check(advanced.findViewById<View>(R.id.tvReceived).isShown)
            check(!advanced.findViewById<View>(R.id.btnScan).isShown)
            check(!advanced.findViewById<View>(R.id.btnStop).isShown)
            check(!advanced.findViewById<View>(R.id.connectionStatusRow).isShown)
            check(advanced.findViewById<Button>(R.id.btnConnect).text.toString() == "保存配对地址")
            advanced.findViewById<android.widget.EditText>(R.id.etAddress).setText("https://pairing.invalid/#" + "a".repeat(32))
            advanced.findViewById<Button>(R.id.btnConnect).performClick()
            advanced.findViewById<Spinner>(R.id.spVoicePicker).setSelection(3)
            advanced.findViewById<Button>(R.id.btnGain180).performClick()
            advanced.findViewById<Button>(R.id.btnRate125).performClick()
        }
        test.waitForIdleSync()
        check(prefs.getInt("voice", -1) == 3 && prefs.getFloat("gain", 0f) == 1.8f)
        check(SpeechRate.percent(app) == 125)
        check(prefs.getString("url", "") == "https://pairing.invalid/#" + "a".repeat(32))
        test.runOnMainSync { ToolbarProbe.back(advanced) }; test.waitForIdleSync()
        awaitFocus(test, speech)
        test.runOnMainSync {
            check(visibleText(speech.window.decorView).contains("音量增强：180%"))
            check(visibleText(speech.window.decorView).contains("朗读语速：1.25×"))
            check(!speech.findViewById<View>(R.id.sbGain).isShown)
            speech.finish()
        }
        prefs.edit().putInt("voice", oldVoice).putFloat("gain", oldGain).apply {
            if(oldUrl == null) remove("url") else putString("url", oldUrl)
        }.commit()
        SpeechRate.save(app, oldRate)

        val store = LearningStore.get(app)
        store.clearLearning()
        val word = store.addWord("settings probe")
        store.setMeaning(word, "设置页测试")
        val home = test.startActivitySync(Intent(app, LearningActivity::class.java).putExtra("page", "review").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LearningActivity
        test.runOnMainSync {
            button(home.window.decorView, "开始复习").performClick()
            button(home.window.decorView, "查看笔记").performClick()
            button(home.window.decorView, "这次记住了").performClick()
        }
        val settingsMonitor = test.addMonitor(MainActivity::class.java.name, null, false)
        test.runOnMainSync { button(home.window.decorView, "设置").performClick() }
        val settings = test.waitForMonitorWithTimeout(settingsMonitor, 10000) as? MainActivity ?: error("没有打开统一设置页")
        test.removeMonitor(settingsMonitor); test.waitForIdleSync()
        test.runOnMainSync {
            check(find(settings.window.decorView, "清空本机学习记录") == null)
            check(button(settings.window.decorView, "配置与测试 AI").visibility == View.VISIBLE)
        }
        val dataMonitor = test.addMonitor(DataSettingsActivity::class.java.name, null, false)
        test.runOnMainSync { button(settings.window.decorView, "数据与关于").performClick() }
        val data = test.waitForMonitorWithTimeout(dataMonitor, 10000) as? DataSettingsActivity ?: error("没有打开数据与关于")
        test.removeMonitor(dataMonitor); test.waitForIdleSync()
        test.runOnMainSync {
            check(button(data.window.decorView, "清空本机学习记录").isShown)
            check(button(data.window.decorView, "开源许可与第三方说明").isShown)
        }
        store.clearLearning()
        test.runOnMainSync { ToolbarProbe.back(data) }; test.waitForIdleSync()
        awaitFocus(test, settings)
        test.runOnMainSync { button(settings.window.decorView, "复习").performClick() }
        test.waitForIdleSync()
        awaitFocus(test, home)
        test.runOnMainSync {
            check(!button(home.window.decorView, "开始复习").isEnabled)
            home.finish()
        }
        return "PASS non-overlapping settings controls, saved-only pairing, voice/gain summaries on return, data/about separation and cleared review state"
    }

    private fun button(root: View, label: String): Button = checkNotNull(find(root, label)) { "找不到按钮：$label" }

    private fun awaitFocus(test: Instrumentation, activity: android.app.Activity) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while(System.nanoTime() < deadline) {
            var focused = false
            test.runOnMainSync { focused = activity.hasWindowFocus() }
            if(focused) return
            Thread.sleep(30)
        }
        error("返回后的页面未获得焦点")
    }

    private fun find(root: View, label: String): Button? {
        if(root.visibility != View.VISIBLE) return null
        if(root is Button && root.text.toString() == label && root.visibility == View.VISIBLE) return root
        if(root is ViewGroup) for(index in 0 until root.childCount) find(root.getChildAt(index), label)?.let { return it }
        return null
    }

    private fun visibleText(root: View): String {
        if(root.visibility != View.VISIBLE) return ""
        if(root is TextView) return root.text.toString()
        return if(root is ViewGroup) (0 until root.childCount).joinToString("\n") { visibleText(root.getChildAt(it)) } else ""
    }
}
