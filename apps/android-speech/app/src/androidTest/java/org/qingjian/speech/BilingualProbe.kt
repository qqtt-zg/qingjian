//! 真实内置中英模型合成、分段重采样与播放器；不调用系统TTS或网络语音。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import org.qingjian.speech.learning.LearningActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BilingualProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext
        val segments=SpeechPlan.split("这次学习 control，接着读 Can you control the volume? 然后继续练习。")
        check(segments.any {it.chinese} && segments.any {!it.chinese} && segments.all {it.text.length<=450})
        val (mixed,rate)=SpeechPlan.merge(listOf(FloatArray(8000){.1f} to 16000,FloatArray(12000){.2f} to 24000))
        check(rate==24000 && mixed.size==25200)
        val engine=BundledVoice.create(app,1)
        try {
            for((text,id) in listOf("这次练习已经完成，请再听一次示范。" to 45,"Can you control the volume?" to 3)) {
                val audio=engine.generate(text,id,1f)
                check(audio.sampleRate==24000 && audio.samples.size>3000 && audio.samples.any {kotlin.math.abs(it)>.001f})
            }
        } finally {engine.release()}
        val activity=test.startActivitySync(Intent(app,LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ended=CountDownLatch(1);var finished=false
        val output=SpeechPlayer(app,{},{});output.onEvent={_,message->if(message.contains("朗读完成")){finished=true;ended.countDown()}else if(message.contains("失败"))ended.countDown()}
        try {output.submit("练习完成。control。再读一次。",false);check(ended.await(90,TimeUnit.SECONDS)&&finished)}finally {output.close();test.runOnMainSync {activity.finish()}}
        return "PASS offline Chinese/English Kokoro synthesis, mixed-language segmentation/resampling and real AudioTrack playback, independent Chinese voice and no network TTS"
    }
}
