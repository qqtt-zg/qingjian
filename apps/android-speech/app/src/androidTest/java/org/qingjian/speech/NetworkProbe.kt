//! 隔离测试链接通过 HTTPS 接收八音色，验证切换音色及试听不会停止已连接会话。
package org.qingjian.speech

import android.content.Context
import android.content.Intent
import android.os.SystemClock

object NetworkProbe {
    fun run(context: Context, url: String): String {
        Pairing.parse(url)
        val prefs=context.getSharedPreferences("speech",0)
        val previousUrl=prefs.getString("url","")
        val previousVoice=prefs.getInt("voice",0)
        val results=mutableListOf<String>()
        try {
            prefs.edit().putString("url",url).commit()
            context.startForegroundService(Intent(context,ReceiverService::class.java).setAction("connect"))
            val ready=SystemClock.elapsedRealtime()+20000
            while(prefs.getString("status","")?.startsWith("已连接")!=true) {
                check(SystemClock.elapsedRealtime()<ready) { "Connection failed: ${prefs.getString("status","")}" }
                Thread.sleep(20)
            }
            for(id in BundledVoice.labels.indices) {
                prefs.edit().putInt("voice",id).commit()
                val received=prefs.getString("last_receive","")
                val deadline=SystemClock.elapsedRealtime()+20000
                val label=BundledVoice.labels[id]
                while(true) {
                    val changed=prefs.getString("last_receive","")!=received
                    val played=prefs.getString("last_playback","") ?: ""
                    if(changed && played.startsWith("$label 朗读完成")) break
                    check(SystemClock.elapsedRealtime()<deadline) { "$label received=$changed playback=$played state=${prefs.getString("status","")}" }
                    Thread.sleep(20)
                }
                results.add("voice $id HTTPS receive and playback passed")
                // 在同一会话中试播一次，再让下一次网络事件验证连接仍存活。
                if(id==0) {
                    context.startForegroundService(Intent(context,ReceiverService::class.java).setAction("test"))
                    Thread.sleep(3000)
                }
            }
        } finally {
            context.stopService(Intent(context,ReceiverService::class.java))
            prefs.edit().putString("url",previousUrl).putInt("voice",previousVoice).commit()
        }
        return results.joinToString("\n")
    }
}
