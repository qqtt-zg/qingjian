//! 用大于单词时长的真实流缓冲区验证起播，不把等待超时当成朗读完成。
package org.qingjian.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

object ShortAudioProbe {
    fun run(context: Context): String {
        val prefs=context.getSharedPreferences("speech",0)
        val oldPack=prefs.getString("pack","builtin")
        val oldVoice=prefs.getInt("voice",1)
        val results=mutableListOf<String>()
        val engine=BundledVoice.create(context,1)
        try {
            for(text in listOf("control","control system")) {
                val audio=engine.generate(text,BundledVoice.speakerId(1),1f)
                val rms=sqrt(audio.samples.sumOf {it.toDouble()*it}/audio.samples.size)
                check(rms>0.001) {"$text synthesis is silent: rms=$rms"}
                results.add("$text: ${audio.samples.size*1000L/audio.sampleRate}ms, rms=$rms")
            }
        } finally {engine.release()}
        prefs.edit().putString("pack","builtin").putInt("voice",1).commit()
        try {
            for(text in listOf("control","control system")) {
                val done=CountDownLatch(1)
                val output=AtomicReference<AudioTrack?>(null)
                val completedFrames=AtomicReference(0)
                val failure=AtomicReference<String?>(null)
                val player=SpeechPlayer(context,{message ->
                    if(message.contains("朗读完成")) completedFrames.set(output.get()!!.playbackHeadPosition)
                    if(message.contains("失败") || message.contains("超时") || message.contains("无法播放")) failure.set(message)
                },{done.countDown()},trackFactory={attributes,rate,_ ->
                    AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(rate*2*2).setTransferMode(AudioTrack.MODE_STREAM).build().also {output.set(it)}
                })
                try {
                    val book=org.qingjian.speech.learning.LearningStore.get(context)
                    val before=book.words(text).firstOrNull {it.english.equals(text,true)}?.played ?: 0
                    player.submit(text,learn=true)
                    check(done.await(60,TimeUnit.SECONDS)) {"$text timed out"}
                    check(failure.get()==null) {failure.get() ?: "playback failed"}
                    check(completedFrames.get()>0) {"$text reported completion but playback head never advanced"}
                    check(book.words(text).first {it.english.equals(text,true)}.played==before+1) {"Completed playback was not recorded exactly once"}
                    results.add("$text: large-buffer playback advanced ${completedFrames.get()} frames")
                } finally {player.close()}
            }
        } finally {prefs.edit().putString("pack",oldPack).putInt("voice",oldVoice).commit()}
        return "PASS short audio playback\n"+results.joinToString("\n")
    }
}
