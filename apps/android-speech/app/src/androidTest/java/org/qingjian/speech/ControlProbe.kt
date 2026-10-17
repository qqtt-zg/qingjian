//! 真实 HTTPS 控制、模型传输和动态加载；只连接隔离测试服务。
package org.qingjian.speech

import android.content.Context
import android.content.Intent
import android.os.SystemClock

object ControlProbe {
    fun run(context:Context,url:String,pack:String):String {
        val prefs=context.getSharedPreferences("speech",0)
        fun await(label:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+90000
            while(!condition()) {
                check(SystemClock.elapsedRealtime()<deadline) {"$label: ${prefs.getString("status","")}"}
                Thread.sleep(50)
            }
        }
        val oldUrl=prefs.getString("url","");val oldPack=prefs.getString("pack","builtin");val oldVoice=prefs.getInt("voice",0)
        try {
            prefs.edit().putString("url",url).putString("pack","builtin").putInt("voice",0).putString("last_playback","").commit()
            context.startForegroundService(Intent(context,ReceiverService::class.java).setAction("connect"))
            await("remote voice playback") {prefs.getString("last_playback","")?.startsWith("女声 1 朗读完成")==true}
            await("remote pause") {!prefs.getBoolean("remote_enabled",true)}
            await("download and load model") {prefs.getString("pack","")==pack && java.io.File(VoiceLibrary.directory(context,pack),"speakers.txt").isFile}
            await("dynamic model playback") {prefs.getString("last_playback","")?.startsWith("音色 1 朗读完成")==true}
            check(VoiceLibrary.count(context,pack)==8)
            await("reject unsafe package") {prefs.getString("status","")?.startsWith("音色传输失败")==true}
            check(prefs.getString("pack","")==pack) {"Failed package replaced active model"}
            check(!java.io.File(context.filesDir,"escaped.txt").exists())
            return "PASS remote voice selection, pause, authenticated model download/hash, dynamic model playback, unsafe-package rejection and active voice retention"
        } finally {
            context.stopService(Intent(context,ReceiverService::class.java))
            prefs.edit().putString("url",oldUrl).putString("pack",oldPack).putInt("voice",oldVoice).commit()
        }
    }
}
