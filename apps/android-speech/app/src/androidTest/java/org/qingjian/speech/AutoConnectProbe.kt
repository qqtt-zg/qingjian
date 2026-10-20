//! 隔离模拟器验证扫码结果自动连接一次、返回来源页及常驻图标直接控制接收。
package org.qingjian.speech

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.widget.TextView
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.SettingsHomeActivity
import org.qingjian.speech.learning.TutorActivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object AutoConnectProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext; val ui = ProbeUi(test)
        val handshakes = AtomicInteger()
        val prefs = app.getSharedPreferences("speech", 0)
        val token = "a".repeat(32)
        val pairing = "https://auto-connect.invalid/#$token"
        prefs.edit().remove("url").putString("status", "尚未连接电脑").putString("connection_status", "尚未连接电脑")
            .putString("pack", "builtin").putInt("voice", 0).commit()
        URL.setURLStreamHandlerFactory { protocol -> if(protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                check(url.host == "auto-connect.invalid")
                return object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getResponseCode(): Int {
                        check(getRequestProperty("Authorization") == "Bearer $token")
                        if(url.path == "/api/events") Thread.sleep(50)
                        return 200
                    }
                    override fun getInputStream(): ByteArrayInputStream {
                        if(url.path == "/api/status") handshakes.incrementAndGet()
                        return ByteArrayInputStream((if(url.path == "/api/update") """{"update":null}"""
                            else """{"sequence":1,"control":{"revision":1,"enabled":true,"pack":"builtin","speaker":0}}""").toByteArray())
                    }
                }
            }
        } }
        val home = test.startActivitySync(Intent(app, LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("home focus") { home.hasWindowFocus() }
        ui.main {
            check(home.findViewById<android.view.View>(R.id.reconnectConnect) == null)
            check(ui.target(home.window.decorView, "设置") != null)
        }
        fun scan(result: String?) {
            val data = result?.let { Intent().putExtra("SCAN_RESULT", it).putExtra("SCAN_RESULT_FORMAT", "QR_CODE") }
            val interception = test.addMonitor(ScanActivity::class.java.name, Instrumentation.ActivityResult(
                if(result == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, data), true)
            val hostMonitor = test.addMonitor(MainActivity::class.java.name, null, false)
            ui.click(home, "扫码连接电脑")
            val host = test.waitForMonitorWithTimeout(hostMonitor, 15000) ?: error("扫码宿主未启动")
            test.removeMonitor(hostMonitor)
            ui.await("scan returns to source") { home.hasWindowFocus() && (host.isFinishing || host.isDestroyed) }
            check(interception.hits == 1); test.removeMonitor(interception)
        }
        scan(pairing)
        ui.await("automatic connection") { handshakes.get() == 1 && prefs.getString("connection_status", "").orEmpty().contains("已连接") }
        check(prefs.getString("url", "") == pairing)
        ui.main { home.findViewById<android.view.View>(R.id.connectionSummary).performClick() }
        DialogProbe.click(test, "重新连接电脑")
        ui.await("one reconnect") { handshakes.get() == 2 }
        test.uiAutomation.waitForIdle(500, 5000)
        check(handshakes.get() == 2)
        DialogProbe.click(test, "关闭连接面板")
        test.waitForIdleSync()
        val image = checkNotNull(test.uiAutomation.takeScreenshot())
        File(app.filesDir, "ui-review").mkdirs()
        File(app.filesDir, "ui-review/connection-icons.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        ui.main { home.findViewById<android.view.View>(R.id.connectionSummary).performClick() }
        DialogProbe.click(test, "停止连接")
        DialogProbe.click(test, "关闭连接面板")
        ui.await("stopped from bar") { prefs.getString("status", "") == "已停止" }
        ui.await("stop stays visible") { home.findViewById<TextView>(R.id.connectionSummary).text.contains("未连接") }
        val status = prefs.getString("connection_status", "")
        scan("javascript:invalid")
        check(handshakes.get() == 2 && prefs.getString("url", "") == pairing && prefs.getString("connection_status", "") == status)
        scan(null)
        check(handshakes.get() == 2 && prefs.getString("url", "") == pairing && prefs.getString("connection_status", "") == status)
        ui.click(home, "词库")
        ui.main { check(ui.target(home.window.decorView, "设置") == null) }
        ui.click(home, "学习"); ui.click(home, "复习一下，记得更牢")
        ui.main { check(ui.target(home.window.decorView, "设置") == null); ToolbarProbe.back(home) }
        val monitor = test.addMonitor(TutorActivity::class.java.name, null, false)
        ui.click(home, "AI 老师")
        val ai = test.waitForMonitorWithTimeout(monitor, 15000) ?: error("AI page did not open")
        test.removeMonitor(monitor); ui.await("AI focused") { ai.hasWindowFocus() }
        ui.main { check(ui.target(ai.window.decorView, "设置") == null); ai.finish() }
        ui.await("home restored") { home.hasWindowFocus() }
        val settingsMonitor = test.addMonitor(SettingsHomeActivity::class.java.name, null, false)
        ui.click(home, "设置")
        val settings = test.waitForMonitorWithTimeout(settingsMonitor, 15000) ?: error("home settings did not open")
        test.removeMonitor(settingsMonitor)
        ui.main { settings.finish(); home.finish() }
        return "PASS automatic scan: result starts one authenticated session and returns, panel reconnect/stop work, invalid/cancel preserve pairing/state, settings appears only on learning home"
    }
}
