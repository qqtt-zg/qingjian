//! 验证内置 af_heart 的短词合成、默认选择及与轻量音色互切后的真实播放。
package org.qingjian.speech

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

object HeartProbe {
    fun run(context: Context): String {
        val prefs=context.getSharedPreferences("speech",0)
        val oldPack=prefs.getString("pack","builtin")
        val hadVoice=prefs.contains("voice")
        val oldVoice=prefs.getInt("voice",0)
        val done=AtomicReference(CountDownLatch(1))
        val complete=AtomicReference("")
        val failure=AtomicReference<String?>(null)
        val player=SpeechPlayer(context,{ message ->
            if(message.contains("朗读完成")) complete.set(message)
            if(message.contains("失败") || message.contains("无法播放")) failure.set(message)
        },{done.get().countDown()})
        try {
            prefs.edit().remove("voice").commit()
            BundledVoice.initializeSelection(context)
            check(prefs.getInt("voice",-1)==1)
            BundledVoice.initializeSelection(context)
            check(prefs.getInt("voice",-1)==1)
            check(BundledVoice.speakerId(1)==3 && BundledVoice.engineKey(1)!=BundledVoice.engineKey(0))
            val engine=BundledVoice.create(context,1)
            try {
                check(engine.numSpeakers()>3)
                for(text in listOf("I","hello","water","develop")) {
                    val generated=engine.generate(text,BundledVoice.speakerId(1),1f)
                    check(generated.sampleRate==24000 && generated.samples.isNotEmpty())
                    check(generated.samples.any {it.isFinite() && abs(it)>0.00001f}) {"af_heart $text output is silent"}
                }
            } finally {engine.release()}
            prefs.edit().putString("pack","builtin").commit()
            for(voice in listOf(0,1,2,1)) {
                prefs.edit().putInt("voice",voice).commit()
                done.set(CountDownLatch(1));complete.set("");failure.set(null)
                player.submit("Hello. English speech is ready.")
                check(done.get().await(60,TimeUnit.SECONDS)) {"voice $voice timed out"}
                check(failure.get()==null) {failure.get() ?: "playback failed"}
                check(complete.get().startsWith(BundledVoice.labels[voice]+" 朗读完成")) {complete.get()}
            }
            return "PASS bundled af_heart sid 3, default/preserved selection, four short words, Kitten/Kokoro playback switching and replay without model download"
        } finally {
            player.close()
            val edit=prefs.edit().putString("pack",oldPack)
            if(hadVoice) edit.putInt("voice",oldVoice) else edit.remove("voice")
            edit.commit()
        }
    }
}
