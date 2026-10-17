//! 八音色逐个经过实际 SpeechPlayer 播放与焦点流程，不只检查模型输出。
package org.qingjian.speech

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PlaybackProbe {
    fun run(context: Context): String {
        val prefs=context.getSharedPreferences("speech",0)
        val previous=prefs.getInt("voice",0)
        val done=AtomicReference(CountDownLatch(1))
        val failure=AtomicReference<String?>(null)
        val played=AtomicReference(false)
        val player=SpeechPlayer(context,{ message ->
            if(message.startsWith("正在朗读")) played.set(true)
            if(message.contains("失败") || message.contains("无法播放")) failure.set(message)
        },{done.get().countDown()})
        val results=mutableListOf<String>()
        try {
            for(id in BundledVoice.labels.indices) {
                prefs.edit().putInt("voice",id).commit()
                done.set(CountDownLatch(1)); played.set(false); failure.set(null)
                player.submit("Hello. English speech is ready.")
                check(done.get().await(20,TimeUnit.SECONDS)) {"voice $id timed out"}
                check(failure.get()==null && played.get()) {"voice $id: ${failure.get()}"}
                results.add("voice $id playback passed")
            }
        } finally { player.close();prefs.edit().putInt("voice",previous).commit() }
        return results.joinToString("\n")
    }
}
