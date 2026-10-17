//! 前台录音、跟读回放与独立自评；当前不上传录音、不生成自动发音分数。
package org.qingjian.speech.learning.oral

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import org.qingjian.speech.ReceiverService
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.practice.PracticeStore
import java.io.File

class OralActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { LearningStore.get(this) }
    private val practice by lazy { PracticeStore(this) }
    private var wordId = 0L
    private var recorder: Recorder? = null
    private var captured: Capture? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var notice = "先听示范，等朗读结束后再开始录音。录音仅保存在本机。"
    private var started = 0L
    private var stopping = false
    private var resumed = false
    private val limit = Runnable {
        if(recorder != null) { stopping = true; recorder?.stop(); if(resumed) render() }
    }
    private val tick = object : Runnable {
        override fun run() {
            if(!resumed || recorder == null) return
            status.text = if(stopping) "正在完成录音…" else "正在录音 ${(SystemClock.elapsedRealtime() - started) / 1000} / 30 秒"
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = Views(this); wordId = state?.getLong("word_id") ?: intent.getLongExtra("word_id", 0)
        state?.getString("temporary")?.let { name ->
            runCatching {
                val file = AudioFiles.pending(this, name)
                if(file.isFile) captured = Capture(file, state.getLong("duration"), state.getBoolean("quiet"))
            }
        }
    }

    override fun onResume() { super.onResume(); resumed = true; render() }

    override fun onPause() {
        resumed = false
        if(recorder != null) notice = "离开页面已取消本次录音，麦克风已释放。"
        else if(player != null) notice = "离开页面已停止回放。"
        recorder?.cancel(); recorder = null; stopping = false; handler.removeCallbacks(tick)
        handler.removeCallbacks(limit)
        stopPlayback(); releaseFocus(); super.onPause()
    }

    override fun onDestroy() {
        if(!isChangingConfigurations) captured?.file?.delete()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putLong("word_id", wordId)
        captured?.let { out.putString("temporary", it.file.name); out.putLong("duration", it.duration); out.putBoolean("quiet", it.quiet) }
        super.onSaveInstanceState(out)
    }

    private fun acquireFocus(exclusive: Boolean): Boolean {
        releaseFocus()
        var request: AudioFocusRequest? = null
        request = AudioFocusRequest.Builder(if(exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                if(change < 0 && focus === request) {
                    recorder?.cancel(); recorder = null; stopping = false; handler.removeCallbacks(tick)
                    handler.removeCallbacks(limit)
                    stopPlayback(); releaseFocus(); notice = "音频被打断，请重新开始。"
                    if(resumed) render()
                }
            }, handler).build()
        focus = request
        val granted = getSystemService(AudioManager::class.java).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(!granted) releaseFocus()
        return granted
    }

    private fun releaseFocus() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
    }

    private fun startCapture() {
        if(!resumed || recorder != null) return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 31); return
        }
        stopPlayback()
        if(!acquireFocus(true)) { notice = "暂时无法使用音频，请稍后重试。"; render(); return }
        lateinit var current: Recorder
        current = Recorder(this) { result, message ->
            if(recorder !== current || !resumed) { result?.file?.delete(); return@Recorder }
            recorder = null; stopping = false; handler.removeCallbacks(tick); releaseFocus()
            handler.removeCallbacks(limit)
            if(result != null) { captured?.file?.delete(); captured = result }
            notice = message; render()
        }
        recorder = current; started = SystemClock.elapsedRealtime(); stopping = false
        notice = "正在录音，最多 30 秒"; render(); handler.post(tick); handler.postDelayed(limit, 30000); current.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == 31) {
            if(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && resumed) startCapture()
            else { notice = "未开始录音；需要麦克风权限才能跟读录音。"; if(resumed) render() }
        }
    }

    private fun stopPlayback() { player?.let { runCatching { it.stop() }; it.release() }; player = null }

    private fun play(file: File) {
        stopPlayback()
        if(!file.isFile || !acquireFocus(false)) { notice = "录音不可用或音频正被占用。"; render(); return }
        val output = MediaPlayer(); player = output
        try {
            output.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            output.setDataSource(file.path)
            output.setOnPreparedListener { if(player === it && resumed) it.start() }
            output.setOnCompletionListener { if(player === it) { stopPlayback(); releaseFocus(); notice = "回放结束。"; if(resumed) render() } }
            output.setOnErrorListener { item, _, _ ->
                if(player === item) { stopPlayback(); releaseFocus(); notice = "无法回放该录音。"; if(resumed) render() }; true
            }
            output.prepareAsync(); notice = "正在回放录音…"; render()
        } catch (_: Exception) { stopPlayback(); releaseFocus(); notice = "无法回放该录音。"; render() }
    }

    private fun render() {
        if(wordId == 0L) { picker(); return }
        val word = store.word(wordId)
        if(word == null) { finish(); return }
        val column = ui.column()
        column.addView(ui.title(word.english))
        column.addView(ui.text(word.meaning, muted = true))
        column.addView(ui.text("跟读与自评独立记录。自动发音评估尚未配置，当前不会判断读音正误或生成分数。", 13f, true))
        status = ui.text(notice).apply { id = ID_STATUS; accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(status)
        val busy = recorder != null || player != null
        column.addView(ui.button("听示范") {
            startForegroundService(Intent(this, ReceiverService::class.java).setAction("word").putExtra("text", word.english))
        }.apply { isEnabled = !busy })
        column.addView(ui.button(if(recorder != null) "停止录音" else if(captured != null) "重新录音" else "开始录音", true) {
            if(recorder != null) { stopping = true; recorder?.stop(); render() } else startCapture()
        }.apply { id = ID_RECORD; isEnabled = !stopping && player == null })
        if(player != null) column.addView(ui.button("停止回放") { stopPlayback(); releaseFocus(); notice = "回放已停止。"; render() })
        captured?.let { clip ->
            ui.card(column) { card ->
                card.addView(ui.text("本次录音 · %.1f 秒".format(clip.duration / 1000.0), 18f))
                card.addView(ui.button("试听本次录音") { play(clip.file) }.apply { isEnabled = !busy })
                card.addView(ui.button("保存本次录音", true) {
                    try {
                        practice.saveRecording(wordId, word.english, clip.file, clip.duration)
                        captured = null; notice = "录音已保存在本机，可以回放与自评；自动评估待接入。"
                    } catch(error: Exception) { notice = error.message ?: "录音保存失败" }
                    render()
                }.apply { id = ID_SAVE; isEnabled = !busy })
            }
        }
        column.addView(ui.text("最近保存的录音", 20f))
        val recordings = practice.recordings(wordId)
        if(recordings.isEmpty()) column.addView(ui.text("尚未保存录音。", muted = true))
        for(recording in recordings) ui.card(column) { card ->
            val rating = when(recording.rating) { "again" -> "还需练习"; "satisfied" -> "比较满意"; else -> "尚未自评" }
            card.addView(ui.text("%.1f 秒 · 自评：%s".format(recording.duration / 1000.0, rating)))
            card.addView(ui.text("自动评估：未评估", 13f, true))
            card.addView(ui.button("回放录音") { play(AudioFiles.file(this, recording.filename)) }.apply { isEnabled = !busy })
            for((label, value) in listOf("还需练习" to "again", "比较满意" to "satisfied")) {
                card.addView(ui.button(label) { practice.rateRecording(recording.id, value); render() }.apply { isEnabled = !busy })
            }
            card.addView(ui.button("删除这段录音") {
                AlertDialog.Builder(this).setMessage("删除这段录音及其自评记录？词汇与其他练习保留。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> practice.deleteRecording(recording); render() }.show()
            }.apply { isEnabled = !busy })
        }
        setContentView(ui.page("录音跟读", column))
    }

    private fun picker() {
        val column = ui.column()
        column.addView(ui.text("选择一个词或短语，先听示范，再录音跟读。", muted = true))
        val search = ui.input("搜索英文或中文笔记")
        val list = ui.column(); column.addView(search); column.addView(list)
        fun populate() {
            list.removeAllViews()
            val words = store.words(search.text.toString())
            if(words.isEmpty()) list.addView(ui.text("没有找到词汇，请先到词汇本添加。", muted = true))
            for(word in words) list.addView(ui.button(word.english) { wordId = word.id; render() })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { populate() }
        })
        populate(); setContentView(ui.page("选择跟读词汇", column))
    }

    companion object {
        const val ID_RECORD = 0x005a5001
        const val ID_SAVE = 0x005a5002
        const val ID_STATUS = 0x005a5003
    }
}
