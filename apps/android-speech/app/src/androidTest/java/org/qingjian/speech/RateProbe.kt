//! 使用真实 Kokoro 和 Kitten 合成，验证持久化语速确实改变音频时长。
package org.qingjian.speech

import android.content.Context
import org.qingjian.speech.rate.SpeechRate

object RateProbe {
    fun run(context: Context): String {
        val prefs = context.getSharedPreferences("speech", 0)
        val hadRate = prefs.contains(SpeechRate.KEY)
        val oldRate = prefs.getInt(SpeechRate.KEY, 100)
        val results = mutableListOf<String>()
        try {
            prefs.edit().remove(SpeechRate.KEY).commit()
            check(SpeechRate.speed(context) == 1f)
            check(runCatching { SpeechRate.save(context, 0) }.isFailure)
            check(runCatching { SpeechRate.save(context, 205) }.isFailure)
            prefs.edit().putInt(SpeechRate.KEY, 500).commit()
            check(SpeechRate.speed(context) == 2f)
            for(voice in listOf(1, 0)) {
                val engine = BundledVoice.create(context, voice)
                try {
                    val sizes = listOf(75, 100, 150).map { percent ->
                        SpeechRate.save(context, percent)
                        val audio = engine.generate("Please control the volume.", BundledVoice.speakerId(voice), SpeechRate.speed(context))
                        check(audio.sampleRate > 0 && audio.samples.any { it.isFinite() && kotlin.math.abs(it) > 0.00001f })
                        audio.samples.size
                    }
                    check(sizes[0] > sizes[1] * 1.1 && sizes[2] < sizes[1] * 0.9) { "Speech rate did not change duration: $sizes" }
                    results += "${BundledVoice.engineKey(voice)} 0.75x/1x/1.5x samples=$sizes"
                } finally { engine.release() }
            }
        } finally {
            prefs.edit().apply { if(hadRate) putInt(SpeechRate.KEY, oldRate) else remove(SpeechRate.KEY) }.commit()
        }
        return "PASS default/bounded/persistent rate and real synthesis duration\n" + results.joinToString("\n")
    }
}
