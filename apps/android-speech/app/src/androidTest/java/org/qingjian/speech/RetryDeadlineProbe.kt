//! 隔离模拟器以受控断网等待真实60秒，验证重连截止与顶部原页停止。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import org.qingjian.speech.learning.LearningActivity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object RetryDeadlineProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val ui=ProbeUi(test);val requests=AtomicInteger();val prefs=app.getSharedPreferences("speech",0)
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler(){override fun openConnection(url:URL):URLConnection {
            check(url.host=="deadline.invalid")
            return object:HttpURLConnection(url){override fun connect()=Unit;override fun usingProxy()=false;override fun disconnect()=Unit;override fun getOutputStream()=ByteArrayOutputStream();override fun getResponseCode():Int{requests.incrementAndGet();throw IOException("controlled offline")}}
        }}}
        val screen=test.startActivitySync(Intent(app,LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        prefs.edit().putString("url","https://deadline.invalid/#"+"a".repeat(32)).commit()
        app.startForegroundService(Intent(app,ReceiverService::class.java).setAction("connect"))
        ui.await("retry starts"){prefs.getLong("retry_deadline",0)>0}
        val deadline=prefs.getLong("retry_deadline",0);Thread.sleep(2500);check(prefs.getLong("retry_deadline",0)==deadline)
        ui.await("60-second stop",65000){prefs.getString("connection_status","").orEmpty().contains("超时")&&prefs.getString("status","")=="已停止"}
        val count=requests.get();Thread.sleep(2000);check(requests.get()==count)
        ui.main {check(ui.text(screen.window.decorView).contains("重新连接"));check(ui.target(screen.window.decorView,"展开连接面板")==null)}
        ui.main {screen.findViewById<android.view.View>(R.id.connectionSummary).performClick()};ui.await("manual new episode"){prefs.getLong("retry_deadline",0)>deadline}
        ui.main {screen.findViewById<android.view.View>(R.id.connectionSummary).performClick()};ui.await("manual stop"){prefs.getString("connection_status","")=="已停止接收"}
        prefs.edit().remove("url").commit();ui.main {screen.finish()};return "PASS real 60-second retry deadline, backoff never resets clock, no auto-restart, same-page reconnect and stop"
    }
}
