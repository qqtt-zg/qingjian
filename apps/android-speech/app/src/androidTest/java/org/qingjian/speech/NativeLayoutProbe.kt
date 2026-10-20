//! 隔离设备验证三页统一标题栏、真实连接面板、子页返回和自适应启动图标。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.View
import android.widget.TextView
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.SettingsHomeActivity
import org.qingjian.speech.learning.TutorActivity
import java.io.File

object NativeLayoutProbe {
    fun run(test: Instrumentation): String {
        val ui = ProbeUi(test); val app = test.targetContext
        val prefs = app.getSharedPreferences("speech", 0)
        prefs.edit().putString("connection_status", "已连接，等待电脑朗读")
            .putString("url", "https://header.invalid/#" + "a".repeat(32)).commit()
        val directory = File(app.filesDir, "ui-review").apply { mkdirs() }
        fun capture(name: String) {
            test.waitForIdleSync(); test.uiAutomation.waitForIdle(250, 5000)
            val image = checkNotNull(test.uiAutomation.takeScreenshot())
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        var reference: Rect? = null
        for(page in listOf("today", "words", "ai")) {
            val target = if(page == "ai") Intent(app, TutorActivity::class.java).putExtra("home_tab", true)
                else Intent(app, LearningActivity::class.java).putExtra("page", page)
            val activity = test.startActivitySync(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.await("$page page") { activity.hasWindowFocus() }
            ui.main {
                val header = activity.findViewById<View>(R.id.mainHeader)
                val bar = activity.findViewById<View>(R.id.connectionBar)
                val scan = activity.findViewById<View>(R.id.scanConnect)
                val title = activity.findViewById<View>(R.id.mainTitle)
                check(header === bar.parent)
                val bounds = Rect(); val parent = Rect(); val titleBounds = Rect(); val barBounds = Rect()
                check(scan.getGlobalVisibleRect(bounds) && header.getGlobalVisibleRect(parent) && title.getGlobalVisibleRect(titleBounds) && bar.getGlobalVisibleRect(barBounds))
                check(bounds.right <= parent.right && titleBounds.right <= barBounds.left)
                check(scan.width >= (44 * app.resources.displayMetrics.density).toInt())
                check(kotlin.math.abs(bounds.centerY() - parent.centerY()) <= 2)
                reference?.let { check(it == bounds) { "Connection controls moved between main pages" } }
                reference = Rect(bounds)
                check((activity.findViewById<View>(R.id.appSettings) != null) == (page == "today"))
                check(activity.findViewById<View>(R.id.reconnectConnect) == null && activity.findViewById<View>(R.id.stopConnect) == null)
                activity.findViewById<View>(R.id.connectionSummary).performClick()
            }
            ui.await("panel shown") { DialogProbe.exists(test, "关闭连接面板") }
            check(DialogProbe.enabled(test, "重新连接电脑"))
            if(page == "today") {
                capture("connection-panel-${app.resources.configuration.screenWidthDp}.png")
                DialogProbe.click(test, "停止连接")
                ui.await("real stop") { prefs.getString("connection_status", "").orEmpty().contains("已停止") && DialogProbe.exists(test, "电脑未连接") }
                check(prefs.getString("url", "")!!.contains("header.invalid"))
                prefs.edit().putString("connection_status", "网络中断，正在重新连接…").commit()
                ui.await("live connecting") { DialogProbe.exists(test, "正在连接电脑…") && !DialogProbe.enabled(test, "重新连接电脑") }
                prefs.edit().putString("connection_status", "配对已失效").commit()
                ui.await("live expired") { DialogProbe.enabled(test, "重新扫码连接电脑") }
            }
            DialogProbe.click(test, "关闭连接面板")
            ui.await("panel closed") { activity.hasWindowFocus() }
            prefs.edit().putString("connection_status", "电脑已暂停朗读，连接保留").commit()
            ui.await("paused stays connected") { activity.findViewById<TextView>(R.id.connectionSummary).text.contains("已连接") }
            prefs.edit().putString("connection_status", "配对已失效").commit()
            ui.await("full expired label") { activity.findViewById<TextView>(R.id.connectionSummary).text.contains("配对失效") }
            prefs.edit().putString("connection_status", "已连接，等待电脑朗读").commit()
            capture("header-$page-${app.resources.configuration.screenWidthDp}.png")
            if(page == "ai") {
                ui.click(activity, "展开学习对话")
                DialogProbe.click(test, "关闭对话列表")
            }
            ui.main { activity.finish() }
        }
        for(type in listOf(SettingsHomeActivity::class.java, TutorActivity::class.java)) {
            val child = test.startActivitySync(Intent(app, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.await("child focused") { child.hasWindowFocus() }
            ui.main { check(child.findViewById<View>(R.id.connectionBar) == null); ToolbarProbe.back(child) }
        }
        val icon = app.packageManager.getApplicationIcon(app.packageName)
        check(icon is AdaptiveIconDrawable)
        if(android.os.Build.VERSION.SDK_INT >= 33) check(icon.monochrome != null)
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 320, 320); icon.draw(Canvas(image))
        check(image.getPixel(160, 40) == android.graphics.Color.rgb(34, 87, 70))
        File(directory, "launcher-icon.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        return "PASS unified main headers at identical positions in ${app.resources.configuration.screenWidthDp}dp, 44dp scan, single settings entry, live connection panel/stop/expired/paused, child back, adaptive icon and monochrome"
    }
}
