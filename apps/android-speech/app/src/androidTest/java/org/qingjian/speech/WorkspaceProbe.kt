//! 独立模拟器上的整页学习流程：真实录音、受控评测、旋转、练习和小结，无学习页跳转。
package org.qingjian.speech

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.assessment.Client
import org.qingjian.speech.learning.assessment.Credentials
import org.qingjian.speech.learning.assessment.wire.Channel
import org.qingjian.speech.learning.word.State
import org.qingjian.speech.learning.word.OralView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object WorkspaceProbe {
    fun state(page:WordStudyActivity)=WordStudyActivity::class.java.getDeclaredField("state").apply {isAccessible=true}.get(page) as State
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val ui=ProbeUi(test);val book=LearningStore.get(app);book.clearLearning()
        val id=book.addWord("control");val notes=org.qingjian.speech.learning.notes.Store(app)
        check(notes.apply(notes.snapshot(id)!!,org.qingjian.speech.learning.notes.Pack.parse(NotesFixture.pack(),"control"))!=null)
        val other=book.addWord("manage");book.setMeaning(other,"管理");val third=book.addWord("change");book.setMeaning(third,"改变")
        Credentials(app).save(AssessmentFixture.profile)
        test.uiAutomation.grantRuntimePermission(app.packageName,Manifest.permission.RECORD_AUDIO)
        var page=test.startActivitySync(Intent(app,WordStudyActivity::class.java).putExtra("word_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WordStudyActivity
        val monitors=listOf(org.qingjian.speech.learning.oral.OralActivity::class.java,org.qingjian.speech.learning.quiz.QuizActivity::class.java,org.qingjian.speech.learning.TutorActivity::class.java).map {test.addMonitor(it.name,null,false)}
        val ready=AtomicBoolean(false);val calls=AtomicInteger()
        fun client()=Client {object:Channel {
            private val ended=AtomicBoolean();private val closed=AtomicBoolean()
            override fun open(){calls.incrementAndGet()}
            override fun send(text:String){if(JSONObject(text).getJSONObject("data").optInt("status")==2)ended.set(true)}
            override fun receive():String {while(!closed.get() && (!ready.get()||!ended.get()))Thread.sleep(15);check(!closed.get());return AssessmentFixture.response()}
            override fun close(){closed.set(true)}
        }}
        fun install(){org.qingjian.speech.learning.oral.Session::class.java.getDeclaredField("createClient").apply {isAccessible=true}.set(state(page).oral,::client)}
        fun mainAction()=ui.main {check(page.findViewById<android.widget.Button>(WordStudyActivity.ID_PRIMARY).performClick())}
        fun text()=ui.text(page.window.decorView)
        fun capture(name:String){test.waitForIdleSync();test.uiAutomation.waitForIdle(250,5000);val bitmap=checkNotNull(test.uiAutomation.takeScreenshot());val dir=File(app.filesDir,"ui-review").apply {mkdirs()};File(dir,"workspace-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        try {
            ui.await("word workspace") {page.hasWindowFocus() && page.findViewById<android.widget.Button>(WordStudyActivity.ID_PRIMARY)?.text=="开始跟读"}
            install();capture("initial")
            ui.main {page.findViewById<android.widget.EditText>(WordStudyActivity.ID_OTHER).setText("manage")}
            mainAction();ui.await("real audio captured") {File(app.cacheDir,"oral").listFiles().orEmpty().any {it.length()>32044}}
            ui.main {check(state(page).oral.phase=="recording" && ui.button(page.window.decorView,"练一下")?.isEnabled==false)}
            mainAction();ui.await("saved recording without navigation") {state(page).oral.phase=="recorded"}
            check(state(page).oral.recording!=null && book.word(id)!!.played==0)
            ui.click(page,"听我的录音");ui.await("inline MediaPlayer finished") {state(page).oral.notice=="回放结束"}
            mainAction();ui.await("first assessment starts") {state(page).oral.phase=="scoring" && calls.get()==1}
            ui.main {check(!page.findViewById<android.widget.Button>(WordStudyActivity.ID_PRIMARY).isEnabled)}
            ui.click(page,"取消评测，保留录音");ui.await("cancel preserves file") {state(page).oral.phase=="recorded"}
            check(org.qingjian.speech.learning.assessment.Store(app).latest(state(page).oral.recording!!.id)!!.state=="cancelled")
            mainAction();ui.await("second assessment starts") {calls.get()==2}
            val retained=state(page);val monitor=test.addMonitor(WordStudyActivity::class.java.name,null,false)
            ui.main {page.recreate()};page=test.waitForMonitorWithTimeout(monitor,15000) as? WordStudyActivity ?: error("workspace recreate failed");test.removeMonitor(monitor)
            ui.await("retained assessment") {page.hasWindowFocus() && state(page).oral.phase=="scoring"}
            check(state(page)===retained && calls.get()==2)
            ui.main {check(page.findViewById<android.widget.EditText>(WordStudyActivity.ID_OTHER).text.toString()=="manage")}
            ready.set(true);ui.await("inline real-shaped score") {state(page).oral.phase=="scored" && text().contains("86.5 / 100")}
            capture("score");check(state(page).oral.assessment!!.result!!.words.first().issue.isNotBlank())
            mainAction();ui.await("quiz on same activity") {state(page).panel=="quiz" && state(page).practice.current!=null}
            val q=state(page).practice.current!!
            ui.click(page,q.english);check(state(page).practice.results.answer(q.key)!!.correct)
            mainAction();ui.await("local summary") {page.findViewById<android.view.View>(WordStudyActivity.ID_SUMMARY).visibility==android.view.View.VISIBLE}
            check(text().contains("已作答 1 / 1，答对 1"));capture("summary")
            ui.main {check(monitors.all {it.hits==0});check(book.word(id)!!.received==0 && book.word(id)!!.reviewed==0)}
            val count=state(page).practice.results.summary(q.kind).first
            val lastMonitor=test.addMonitor(WordStudyActivity::class.java.name,null,false)
            ui.main {page.recreate()};page=test.waitForMonitorWithTimeout(lastMonitor,15000) as? WordStudyActivity ?: error("summary recreate failed");test.removeMonitor(lastMonitor)
            ui.await("summary recreation") {page.hasWindowFocus()}
            check(org.qingjian.speech.learning.practice.PracticeStore(app).summary(q.kind).first==count && calls.get()==2)
            return "PASS single word workspace: real AudioRecord/save/MediaPlayer, cancel retains WAV, one assessment across recreation, real-shaped score and word feedback, inline quiz/summary, retained drafts and zero Oral/Quiz/Tutor activity navigation"
        } finally {
            ready.set(true);for(m in monitors)test.removeMonitor(m)
            ui.main {if(!page.isFinishing)page.finish()};Credentials(app).clear();book.clearLearning()
        }
    }
}
