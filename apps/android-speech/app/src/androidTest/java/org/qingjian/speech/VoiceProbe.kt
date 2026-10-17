//! 同一句英文测量八个内置音色的响度，避免凭听感猜测合成振幅。
package org.qingjian.speech

import android.content.Context
import kotlin.math.abs
import kotlin.math.sqrt

object VoiceProbe {
    fun measure(context: Context, shortWords: Boolean=false): String {
            val lines=BundledVoice.labels.indices.flatMap { id ->
                val engine=BundledVoice.create(context,id)
                try {
                val texts=if(shortWords) listOf("I","hello","me","water","develop") else listOf("I need a glass of water.")
                texts.map { text ->
                val samples=engine.generate(text,BundledVoice.speakerId(id),1f).samples
                check(samples.isNotEmpty()) { "voice=$id text=$text empty output" }
                val rms=sqrt(samples.sumOf { it.toDouble()*it }/samples.size)
                val peak=samples.maxOf { abs(it) }
                val pcm=Pcm.normalize(samples,2f)
                val output=sqrt(pcm.sumOf { (it.toDouble()/32768)*(it.toDouble()/32768) }/pcm.size)
                val boosted=Pcm.normalize(samples,4f)
                val boostRms=sqrt(boosted.sumOf { (it.toDouble()/32768)*(it.toDouble()/32768) }/boosted.size)
                check(boostRms > output)
                check(boosted.all { it.toInt() in -32000..32000 })
                "voice=$id ${if(shortWords) text else "sentence"} rms=%.4f peak=%.4f at200=%.4f at400=%.4f".format(rms,peak,output,boostRms)
                }
                } finally { engine.release() }
            }
            return lines.joinToString("\n")
    }
}
