//! 固定为 16 kHz 单声道 PCM16 的 WAV 文件头，便于本机回放及将来评估接口复用。
package org.qingjian.speech.learning.oral

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wave {
    const val RATE = 16000
    const val MAX_FRAMES = RATE * 30

    fun header(frames: Int): ByteArray {
        require(frames in 0..MAX_FRAMES)
        val size = frames * 2
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(size + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
    }
}
