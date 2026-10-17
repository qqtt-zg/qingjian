//! 将不同语音响度归一化；增益使用软限幅，避免整数溢出产生爆音。
package org.qingjian.speech

import kotlin.math.sqrt
import kotlin.math.tanh

object Pcm {
    fun normalize(samples: FloatArray, volume: Float): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val energy = samples.sumOf { if (it.isFinite()) it.toDouble()*it else 0.0 }
        val rms = sqrt(energy/samples.size)
        val multiplier = if (volume.isFinite()) volume.coerceIn(0.5f,4f) else 1f
        val gain = if (rms > 0.0001) (0.18/rms).coerceIn(0.25,32.0) * multiplier else 1.0
        return ShortArray(samples.size) { i ->
            val sample = if (samples[i].isFinite()) samples[i].toDouble() else 0.0
            (tanh(sample * gain) * 32000).toInt().toShort()
        }
    }
}
