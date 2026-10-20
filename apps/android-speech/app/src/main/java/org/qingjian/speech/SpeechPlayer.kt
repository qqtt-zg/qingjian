//! 内置英文模型合成与媒体播放；新点击替换等待内容，释放焦点后恢复其他音频。
package org.qingjian.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineTts
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.qingjian.speech.rate.SpeechRate

class SpeechPlayer(
    private val context: Context,
    private val report: (String) -> Unit,
    private val idle: () -> Unit,
    // 设备测试可用较大的真实缓冲区复现短音频未达到起播门槛。
    private val trackFactory: ((AudioAttributes, Int, Int) -> AudioTrack)? = null,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val serial = AtomicLong()
    private val monitor = Object()
    private var pending: Triple<Long, String, Boolean>? = null
    private var working = false
    @Volatile private var closed = false
    @Volatile private var track: AudioTrack? = null
    private var engine: OfflineTts? = null
    private var bilingual: OfflineTts? = null
    private var enginePack=""
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun isIdle(): Boolean = synchronized(monitor) { !working && pending == null }

    var onEvent: ((Long, String) -> Unit)? = null

    private fun event(id: Long, message: String) { report(message); onEvent?.invoke(id, message) }

    fun submit(text: String, learn: Boolean = false): Long {
        require(text.isNotBlank() && text.length <= 4000)
        return enqueue(text, learn)
    }

    fun configure() { enqueue("") }

    fun cancelPending() {
        serial.incrementAndGet()
        synchronized(monitor) { pending=null }
    }

    private fun enqueue(text:String, learn: Boolean = false): Long {
        if(closed) return -1
        val id = serial.incrementAndGet()
        synchronized(monitor) {
            pending = Triple(id, text, learn)
            if (!working) {
                working = true
                executor.execute { drain() }
            }
        }
        return id
    }

    private fun drain() {
        drainLoop@ while (!closed) {
            val next = synchronized(monitor) {
                val value=pending;pending=null
                if(value==null)working=false
                value
            } ?: break
            var phase="初始化音色";var usingCustom=false
            val prefs=context.getSharedPreferences("speech",0)
            val pack=prefs.getString("pack","builtin") ?: "builtin"
            val names=VoiceLibrary.names(context,pack)
            val selected=prefs.getInt("voice",0).coerceIn(0,names.lastIndex)
            var label=names[selected]
            val key=if(pack=="builtin") "builtin:${BundledVoice.engineKey(selected)}" else pack
            try {
                val plan=if(next.second.isEmpty())listOf(SpeechSegment("",false)) else SpeechPlan.split(next.second)
                val parts=mutableListOf<Pair<FloatArray,Int>>()
                for(segment in plan) {
                    if(next.first!=serial.get()||closed)continue@drainLoop
                    val useBilingual=segment.chinese || pack=="builtin"&&selected==1
                    val selectedEngine:OfflineTts
                    val voice:Int
                    if(useBilingual) {
                        label=if(segment.chinese)ChineseVoices.label(context) else names[selected]
                        if(bilingual==null){event(next.first,"正在准备中英文音色，首次加载请稍候…");bilingual=BundledVoice.create(context,1)}
                        selectedEngine=bilingual!!
                        voice=if(segment.chinese)ChineseVoices.speaker(context) else BundledVoice.speakerId(selected)
                        usingCustom=false
                    } else {
                        label=names[selected];usingCustom=pack!="builtin"
                        if(engine==null||enginePack!=key){event(next.first,"正在准备音色（$label），首次加载请稍候…");engine?.release();engine=null;engine=VoiceLibrary.create(context,pack,selected);enginePack=key;VoiceLibrary.recordCount(context,pack,engine!!.numSpeakers())}
                        selectedEngine=engine!!
                        voice=if(pack=="builtin")BundledVoice.speakerId(selected) else selected
                    }
                    if(next.second.isEmpty()){event(next.first,"音色已就绪，可试听或朗读");continue@drainLoop}
                    phase="合成";event(next.first,"正在生成语音（$label）…")
                    val generated=selectedEngine.generate(segment.text,voice.coerceIn(0,selectedEngine.numSpeakers()-1),SpeechRate.speed(context))
                    check(generated.samples.isNotEmpty()&&generated.samples.any {it.isFinite()&&kotlin.math.abs(it)>0.00001f})
                    parts+=generated.samples to generated.sampleRate
                }
                if(next.first!=serial.get()||closed)continue
                val (samples,rate)=SpeechPlan.merge(parts);phase="播放"
                if(play(samples,rate,next.first,label)&&next.third)runCatching {org.qingjian.speech.learning.LearningStore.get(context).record(next.second,completed=true)}
            } catch(error:Exception) {
                if(usingCustom){engine?.release();engine=null;enginePack="";prefs.edit().putString("pack","builtin").putInt("voice",0).apply()}
                if(!closed&&next.first==serial.get())event(next.first,"$label $phase 失败（${error.javaClass.simpleName}）"+if(usingCustom)"，已恢复内置音色" else "，请检查音色或稍后重试")
            }
        }
        idle()
    }

    private fun play(samples: FloatArray, rate: Int, id: Long, label: String): Boolean {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                // 旧播放的延迟回调不能取消已经排入的新请求。
                if (change < 0 && serial.compareAndSet(id,id+1) && !closed) event(id, "$label 播放被其他音频打断，请重试")
            }, Handler(Looper.getMainLooper())).build()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            event(id, "$label 暂时无法播放：其他音频占用焦点，请稍后再点喇叭")
            return false
        }
        var output: AudioTrack? = null
        try {
            val gain = context.getSharedPreferences("speech",0).getFloat("gain",1f)
            val normalized = Pcm.normalize(samples, gain)
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            output = trackFactory?.invoke(attributes, rate, minimum * 2) ?: AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(minimum * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            track = output
            output.setVolume(1f)
            // MODE_STREAM 必须填到起播门槛；短词只补末尾静音，不改语音正文和语速。
            val startFrames = if (Build.VERSION.SDK_INT >= 31) {
                maxOf(output.bufferSizeInFrames, output.startThresholdInFrames)
            } else output.bufferSizeInFrames
            val pcm = if (normalized.size < startFrames) normalized.copyOf(startFrames) else normalized
            var offset = output.write(pcm, 0, minOf(pcm.size, output.bufferSizeInFrames), AudioTrack.WRITE_NON_BLOCKING)
            check(offset >= 0)
            if (closed || serial.get() != id) return false
            output.play()
            event(id, "正在朗读（$label）；结束后恢复其他声音")
            while (!closed && serial.get() == id && offset < pcm.size) {
                val count = output.write(pcm, offset, minOf(1024,pcm.size-offset), AudioTrack.WRITE_BLOCKING)
                check(count > 0)
                offset += count
            }
            val deadline = System.nanoTime() + ((pcm.size.toDouble()/rate + 5) * 1e9).toLong()
            while (!closed && serial.get() == id && output.playbackHeadPosition < offset && System.nanoTime() < deadline) Thread.sleep(20)
            if (!closed && serial.get() == id) {
                event(id, if (output.playbackHeadPosition >= offset) "$label 朗读完成，等待电脑点击"
                    else "$label 播放超时，音频未完整输出，请重试")
            }
            return !closed && serial.get() == id && output.playbackHeadPosition >= offset
        } finally {
            track = null
            output?.let { runCatching { it.pause() }; it.release() }
            audio.abandonAudioFocusRequest(focus)
        }
    }

    fun close() {
        closed = true
        serial.incrementAndGet()
        synchronized(monitor) { pending = null }
        executor.execute { engine?.release(); engine = null; bilingual?.release();bilingual=null }
        executor.shutdown()
    }
}
