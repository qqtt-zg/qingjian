//! 词卡内跟读与评测的生命周期；完成的录音立即落盘，取消不丢失录音。
package org.qingjian.speech.learning.oral

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.assessment.Attempt
import org.qingjian.speech.learning.assessment.Client
import org.qingjian.speech.learning.assessment.Credentials
import org.qingjian.speech.learning.assessment.Store
import java.util.concurrent.Executors

class Session(context: Context,val wordId: Long,private val createClient:()->Client={Client()}) {
    private val app=context.applicationContext
    private val book=LearningStore.get(app)
    private val practice=PracticeStore(app)
    private val assessments=Store(app)
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val audio=app.getSystemService(AudioManager::class.java)
    private var recorder: Recorder?=null
    private var player: MediaPlayer?=null
    private var focus: AudioFocusRequest?=null
    private var client: Client?=null
    private var ticket=0L
    private var activeAttempt=""
    private var closed=false
    var changed:(()->Unit)?=null
    var phase="idle"
        private set
    var notice="先听示范，再用自己的声音读出来。"
        private set
    var reference=""
        private set
    var mode="word"
        private set
    var recording: Recording?=null
        private set
    var assessment: Attempt?=null
        private set
    var started=0L
        private set
    val busy get()=phase in listOf("recording","stopping","scoring")
    val playing get()=player!=null
    val elapsed get()=((SystemClock.elapsedRealtime()-started)/1000).coerceIn(0,30)
    private val limit=Runnable {stopRecording()}
    init {
        val previous=practice.recordings(wordId).firstOrNull()
        if(previous!=null)selectRecording(previous)
        else {reference=book.word(wordId)?.english.orEmpty()}
    }
    fun select(text: String,kind: String) {
        if(busy || closed)return
        try {org.qingjian.speech.learning.assessment.Protocol.validate(text,kind)} catch(error:Exception){notice=Client.error(error);changed?.invoke();return}
        if(reference==text && mode==kind)return
        stopPlayback();reference=text;mode=kind;recording=null;assessment=null;phase="idle";notice="先听示范，再点击下方开始跟读。";changed?.invoke()
    }
    fun selectRecording(value: Recording) {
        if(busy || closed || value.wordId!=wordId)return
        stopPlayback();recording=value;reference=value.reference;mode=value.mode;assessment=assessments.latest(value.id)
        if(assessment?.state=="running") {assessments.fail(assessment!!.id,"上次评测中断，录音保留，可手动重试");assessment=assessments.latest(value.id)}
        phase=if(assessment?.state=="succeeded")"scored" else "recorded"
        notice=if(assessment?.state=="failed" || assessment?.state=="cancelled")assessment!!.notice else "录音保存在手机，可试听、评测或重新录制。"
        changed?.invoke()
    }
    fun selectAssessment(value:Recording,id:String) {
        if(busy || closed)return
        selectRecording(value)
        val previous=assessments.history(value.id).firstOrNull {it.id==id} ?: return
        assessment=previous;phase="scored";notice="正在查看这段录音已保存的历史评分。";changed?.invoke()
    }
    private fun focus(exclusive: Boolean): Boolean {
        releaseFocus()
        lateinit var request: AudioFocusRequest
        request=AudioFocusRequest.Builder(if(exclusive)AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({change->if(change<0 && focus===request){stopRecording();stopPlayback();notice="音频被其他应用打断，已保留完成的录音。";changed?.invoke()}},main).build()
        focus=request
        val allowed=audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(!allowed)releaseFocus()
        return allowed
    }
    private fun releaseFocus(){focus?.let {audio.abandonAudioFocusRequest(it)};focus=null}
    fun startRecording() {
        if(busy || closed)return
        val word=book.word(wordId) ?: return
        val target=reference;val kind=mode
        try {org.qingjian.speech.learning.assessment.Protocol.validate(target,kind)} catch(error:Exception){notice=Client.error(error);changed?.invoke();return}
        stopPlayback();if(!focus(true)){notice="暂时无法取得音频焦点，请稍后重试";changed?.invoke();return}
        lateinit var current: Recorder
        current=Recorder(app) {clip,message->
            if(recorder!==current){clip?.file?.delete();return@Recorder}
            recorder=null;main.removeCallbacks(limit);releaseFocus()
            if(clip!=null) {
                try {
                    val id=practice.saveRecording(wordId,word.english,clip.file,clip.duration,target,kind)
                    recording=practice.recordings(wordId).first {it.id==id};assessment=null;phase="recorded"
                    notice=if(clip.quiet)"录音已保存，但声音较弱，建议先试听再评测。" else "录音已保存，点击评估发音可获取反馈。"
                } catch(_: Exception){clip.file.delete();phase=if(recording==null)"idle" else "recorded";notice="录音未能保存，请检查手机空间或词条是否仍然存在。"}
            } else {phase=if(recording==null)"idle" else if(assessment?.state=="succeeded")"scored" else "recorded";notice=message}
            if(!closed)changed?.invoke()
        }
        recorder=current;started=SystemClock.elapsedRealtime();phase="recording";notice="正在录音，最多30秒";current.start();main.postDelayed(limit,30000);changed?.invoke()
    }
    fun stopRecording(){if(recorder!=null && phase=="recording"){phase="stopping";recorder?.stop();main.removeCallbacks(limit);changed?.invoke()}}
    fun discardRecording(){recorder?.cancel();recorder=null;main.removeCallbacks(limit);releaseFocus();phase=if(recording==null)"idle" else "recorded";notice="已取消新录音，之前保存的录音保留。";changed?.invoke()}
    fun play() {
        if(busy || closed)return
        if(player!=null){stopPlayback();notice="已停止回放";changed?.invoke();return}
        val value=recording ?: return
        if(!focus(false)){notice="其他音频占用中，请稍后重试";changed?.invoke();return}
        val output=MediaPlayer();player=output
        try {
            output.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            output.setDataSource(AudioFiles.file(app,value.filename).path)
            output.setOnPreparedListener {if(player===it && !closed)it.start()}
            output.setOnCompletionListener {if(player===it){stopPlayback();notice="回放结束";changed?.invoke()}}
            output.setOnErrorListener {item,_,_->if(player===item){stopPlayback();notice="这段录音无法回放，请重录";changed?.invoke()};true}
            output.prepareAsync();notice="正在回放自己的录音…"
        } catch(_: Exception){stopPlayback();notice="无法回放，请检查录音是否仍在手机中"}
        changed?.invoke()
    }
    fun stopPlayback(){player?.let {runCatching {it.stop()};it.release()};player=null;if(recorder==null)releaseFocus()}
    fun forgetRecording(id:Long){if(recording?.id!=id)return;cancelAssessment();stopPlayback();recording=null;assessment=null;phase="idle";notice="已删除这段录音及评分，可以重新跟读。";changed?.invoke()}
    fun assess() {
        if(busy || closed)return
        val value=recording ?: return
        val profile=try {Credentials(app).load().also {it.validate()}} catch(error: Exception){notice=Client.error(error);changed?.invoke();return}
        stopPlayback()
        val attempt=try {assessments.start(value)} catch(_: Exception){notice="录音或词条不可用，未提交评测，请重新选择或录音";changed?.invoke();return}
        val token=++ticket;activeAttempt=attempt.id;assessment=attempt;phase="scoring";notice="正在准备评测…";val request=createClient();client=request;changed?.invoke()
        worker.execute {
            val result=runCatching {request.assess(profile,AudioFiles.file(app,value.filename),value.reference,value.mode){stage->
                main.post {if(!closed && ticket==token && phase=="scoring"){notice=stage;changed?.invoke()}}
            }}
            main.post {
                if(closed || ticket!=token)return@post
                client=null;activeAttempt=""
                val same=runCatching {Credentials(app).load().same(profile)}.getOrDefault(false)
                if(!same){assessments.fail(attempt.id,"评测配置已变化，本次结果未保存",true);notice="评测配置已变化，录音保留，可重新评测"}
                else result.onSuccess {score->
                    val saved=runCatching {assessments.finish(attempt.id,score)}.getOrDefault(false)
                    if(!saved)assessments.fail(attempt.id,"录音已变化、删除或保存失败，未写入结果")
                    notice=if(saved)"评测已保存，可查看词级反馈，或继续练习。" else "录音已变化、删除或存储失败，未保存晚到结果。"
                }.onFailure {assessments.fail(attempt.id,Client.error(it));notice=Client.error(it)}
                assessment=assessments.latest(value.id);phase=if(assessment?.state=="succeeded")"scored" else "recorded";changed?.invoke()
            }
        }
    }
    fun cancelAssessment() {
        if(phase!="scoring")return
        ++ticket;client?.cancel();client=null
        if(activeAttempt.isNotEmpty())assessments.fail(activeAttempt,"评测已取消，录音保留",true)
        activeAttempt="";assessment=recording?.let {assessments.latest(it.id)};phase="recorded";notice="评测已取消，录音保留；已发出的请求仍可能使用服务额度";changed?.invoke()
    }
    fun pause(changing: Boolean){stopRecording();stopPlayback();if(!changing)cancelAssessment()}
    fun close(){changed=null;cancelAssessment();stopRecording();stopPlayback();closed=true;worker.shutdown()}
}
