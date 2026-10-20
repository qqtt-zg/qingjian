//! 隔离模拟器验证实时置顶状态、图标扫码入口与取消扫码保留原连接。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.widget.ScrollView
import android.widget.TextView
import org.qingjian.speech.learning.LearningActivity
import java.io.File

object ConnectionUiProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext; val ui = ProbeUi(test)
        val prefs = app.getSharedPreferences("speech", 0)
        prefs.edit().remove("connection_status").putString("status", "尚未连接电脑").commit()
        val home = test.startActivitySync(Intent(app, LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("home") { home.hasWindowFocus() }
        ui.main {
            check(home.findViewById<android.view.View>(R.id.scanConnect).contentDescription == "扫码连接电脑")
            prefs.edit().putString("status", "网络中断，正在重新连接…").apply()
        }
        ui.await("status updates") { home.findViewById<TextView>(R.id.connectionSummary).text.contains("连接中") }
        var before = 0
        ui.main {
            val point = IntArray(2); home.findViewById<android.view.View>(R.id.connectionBar).getLocationOnScreen(point); before = point[1]
            fun scroll(view: android.view.View): ScrollView? {
                if(view is ScrollView) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) scroll(view.getChildAt(i))?.let { return it }
                return null
            }
            scroll(home.window.decorView)?.fullScroll(android.view.View.FOCUS_DOWN)
        }
        ui.main {
            val point = IntArray(2); home.findViewById<android.view.View>(R.id.connectionBar).getLocationOnScreen(point)
            check(point[1] == before)
        }
        val connectionMonitor = test.addMonitor(MainActivity::class.java.name, null, false)
        val scanMonitor = test.addMonitor(ScanActivity::class.java.name, null, false)
        ui.click(home, "扫码连接电脑")
        val connection = test.waitForMonitorWithTimeout(connectionMonitor, 15000) as? MainActivity ?: error("没有打开扫码宿主")
        val scan = test.waitForMonitorWithTimeout(scanMonitor, 15000) ?: error("没有启动扫码")
        test.removeMonitor(connectionMonitor); test.removeMonitor(scanMonitor)
        ui.main { scan.finish() }
        ui.await("scan return") { home.hasWindowFocus() && (connection.isFinishing || connection.isDestroyed) }
        val details = test.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ui.await("details focus") { details.hasWindowFocus() }
        check(prefs.getString("status", "").orEmpty().contains("重新连接"))
        ui.main {
            val scanIcon = details.findViewById<android.widget.Button>(R.id.btnScan)
            check(scanIcon.text.isEmpty() && scanIcon.contentDescription == "扫码连接电脑")
            val status = details.findViewById<TextView>(R.id.tvStatus)
            val beforePoint = IntArray(2); status.getLocationOnScreen(beforePoint)
            details.findViewById<ScrollView>(R.id.rootScroll).fullScroll(android.view.View.FOCUS_DOWN)
            val afterPoint = IntArray(2); status.getLocationOnScreen(afterPoint)
            check(beforePoint[1] == afterPoint[1]); ToolbarProbe.back(details)
        }
        ui.await("home returned") { home.hasWindowFocus() }
        prefs.edit().putString("status", "已连接，等待电脑朗读").commit()
        ui.await("connected status") { home.findViewById<TextView>(R.id.connectionSummary).text.contains("已连接") }
        test.waitForIdleSync(); test.uiAutomation.waitForIdle(500, 5000)
        val screenshot = checkNotNull(test.uiAutomation.takeScreenshot())
        File(app.filesDir, "ui-review").mkdirs()
        File(app.filesDir, "ui-review/pinned-status.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle(); ui.main { home.finish() }
        return "PASS pinned status: live preference refresh, scroll-stable status, accessible scan icons, scanner launch/cancel, preserved connection status and fixed connection page header"
    }
}
