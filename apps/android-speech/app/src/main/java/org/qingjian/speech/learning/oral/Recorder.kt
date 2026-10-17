//! 用户明确启动的前台短录音；停止或离开页面后释放麦克风，最多三十秒。
package org.qingjian.speech.learning.oral

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class Recorder(context: Context, private val done: (Capture?, String) -> Unit) {
    private val app = context.applicationContext
    private val file = AudioFiles.temporary(context)
    private val running = AtomicBoolean(true)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var audio: AudioRecord? = null

    fun start() {
        Thread({
            var result: Capture? = null
            var message = "录音失败，请检查麦克风是否可用"
            try {
                if(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw SecurityException()
                val minimum = AudioRecord.getMinBufferSize(Wave.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wave.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minimum * 2, 6400))
                audio = input
                check(input.state == AudioRecord.STATE_INITIALIZED && running.get())
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                var frames = 0; var peak = 0
                RandomAccessFile(file, "rw").use { output ->
                    output.write(Wave.header(0))
                    val buffer = ShortArray(1600)
                    val bytes = ByteArray(buffer.size * 2)
                    while(running.get() && frames < Wave.MAX_FRAMES) {
                        val count = input.read(buffer, 0, minOf(buffer.size, Wave.MAX_FRAMES - frames), AudioRecord.READ_BLOCKING)
                        if(!running.get()) break
                        check(count > 0)
                        for(i in 0 until count) {
                            val sample = buffer[i].toInt()
                            bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte()
                            peak = max(peak, abs(sample))
                        }
                        output.write(bytes, 0, count * 2); frames += count
                    }
                    output.seek(0); output.write(Wave.header(frames))
                }
                if(frames >= Wave.RATE / 2) {
                    result = Capture(file, frames * 1000L / Wave.RATE, peak < 100)
                    message = if(peak < 100) "录音完成，但声音很弱；建议检查麦克风并试听后保存。" else "录音完成，可以试听或保存。"
                } else message = "录音不足 0.5 秒，请再读一次。"
            } catch (_: Exception) { }
            finally {
                running.set(false)
                audio?.let { runCatching { it.stop() }; it.release() }; audio = null
                if(result == null || cancelled.get()) file.delete()
                val captured = result; val notice = message
                Handler(Looper.getMainLooper()).post {
                    if(cancelled.get()) { file.delete(); done(null, "本次录音已取消") }
                    else done(captured, notice)
                }
            }
        }, "qingjian-oral-recording").start()
    }

    fun stop() { running.set(false); audio?.let { runCatching { it.stop() } } }

    fun cancel() { cancelled.set(true); stop() }
}
