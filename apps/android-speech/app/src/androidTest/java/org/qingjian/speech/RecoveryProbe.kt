//! 受控 HTTPS 长轮询断开一次后恢复，确认顶部状态不会继续停在重连提示。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object RecoveryProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val events = AtomicInteger()
        val prefs = app.getSharedPreferences("speech", 0)
        URL.setURLStreamHandlerFactory { protocol -> if(protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                check(url.host == "recovery.invalid")
                return object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getResponseCode(): Int {
                        if(url.path.startsWith("/api/events")) {
                            if(events.incrementAndGet() == 1) throw IOException("controlled disconnect")
                            Thread.sleep(100)
                        }
                        return 200
                    }
                    override fun getInputStream() = ByteArrayInputStream((if(url.path == "/api/update") """{"update":null}"""
                        else """{"sequence":1,"control":{"revision":1,"enabled":true,"pack":"builtin","speaker":1}}""").toByteArray())
                }
            }
        } }
        val screen = test.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ProbeUi(test).await("connection foreground") { screen.hasWindowFocus() }
        prefs.edit().putString("pack", "builtin").putInt("voice", 1).putString("url", "https://recovery.invalid/#"+"a".repeat(32)).putString("status", "测试连接").commit()
        app.startForegroundService(Intent(app, ReceiverService::class.java).setAction("connect"))
        val ui = ProbeUi(test)
        ui.await("disconnect visible", 15000) { prefs.getString("connection_status", "").orEmpty().contains("重新连接") }
        ui.await("recovered", 15000) { prefs.getString("connection_status", "").orEmpty() == "已连接，继续接收朗读" }
        check(events.get() >= 2)
        app.stopService(Intent(app, ReceiverService::class.java))
        ui.await("service stopped") { prefs.getString("status", "") == "已停止" }
        prefs.edit().remove("url").commit()
        test.runOnMainSync { screen.finish() }
        return "PASS network recovery: long-poll exception displays reconnecting, success restores connected status, stop cleans up"
    }
}
