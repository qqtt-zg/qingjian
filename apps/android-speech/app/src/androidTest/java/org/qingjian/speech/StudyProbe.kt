//! 词卡内 AI 问答与真实软键盘：显式请求、评分上下文、旋转草稿与离线回看。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.WindowInsets
import android.widget.EditText
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.word.TeacherView
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.ToolSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object StudyProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val ui=ProbeUi(test);val book=LearningStore.get(app);book.clearLearning()
        val id=book.addWord("realize");book.setMeaning(id,"意识到；实现")
        val credentials=Credentials(app);credentials.save(Profile("https://study-probe.invalid/v1","probe","fake-study-key"));ToolSettings.save(app,false)
        val calls=AtomicInteger();val ready=AtomicBoolean(false);var sent=ByteArrayOutputStream()
        URL.setURLStreamHandlerFactory {protocol->if(protocol!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection {
                check(url.host=="study-probe.invalid");sent=ByteArrayOutputStream()
                return object:HttpURLConnection(url) {
                    override fun connect()=Unit
                    override fun disconnect()=Unit
                    override fun usingProxy()=false
                    override fun getOutputStream()=sent
                    override fun getResponseCode():Int {calls.incrementAndGet();while(!ready.get())Thread.sleep(20);return 200}
                    override fun getInputStream()=ByteArrayInputStream(NotesFixture.answer("realize 可以表示意识到；这里是一条已保存的讲解。").toByteArray())
                }
            }
        }}
        fun open()=test.startActivitySync(Intent(app,WordStudyActivity::class.java).putExtra("word_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WordStudyActivity
        var page=open();val monitor=test.addMonitor(org.qingjian.speech.learning.TutorActivity::class.java.name,null,false)
        try {
            ui.await("word page"){page.hasWindowFocus()};ui.click(page,"问老师")
            ui.await("real IME visible",20000){page.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true}
            ui.main {
                val input=page.findViewById<EditText>(TeacherView.ID_QUESTION);input.setText("realize 和 implement 在实现计划时如何区别？")
            }
            ui.await("visible inline AI form") {page.findViewById<android.view.View>(TeacherView.ID_SEND).getGlobalVisibleRect(Rect())}
            ui.main {
                val input=page.findViewById<EditText>(TeacherView.ID_QUESTION)
                val rect=Rect();check(input.getGlobalVisibleRect(rect) && rect.height()>0)
                check(page.findViewById<android.view.View>(TeacherView.ID_SEND).getGlobalVisibleRect(Rect()))
                check(calls.get()==0);page.findViewById<android.view.View>(TeacherView.ID_SEND).performClick()
            }
            ui.await("single inline request"){calls.get()==1}
            val oldState=WorkspaceProbe.state(page);val rotate=test.addMonitor(WordStudyActivity::class.java.name,null,false)
            ui.main {page.recreate()};page=test.waitForMonitorWithTimeout(rotate,15000) as? WordStudyActivity ?: error("AI recreate failed");test.removeMonitor(rotate)
            ui.await("retained AI"){page.hasWindowFocus() && WorkspaceProbe.state(page)===oldState};check(calls.get()==1)
            ready.set(true);ui.await("inline answer saved"){book.messages("word:$id").lastOrNull()?.role=="assistant"}
            ui.await("inline answer rendered"){ui.text(page.window.decorView).contains("这里是一条已保存的讲解")}
            val body=JSONObject(sent.toString("UTF-8")).toString();check(body.contains("意识到；实现") && body.contains("recent_assessments") && body.contains("current_word_practice") && !body.contains("fake-study-key"))
            ui.main {page.findViewById<EditText>(WordStudyActivity.ID_OTHER).setText("implement");page.findViewById<EditText>(WordStudyActivity.ID_CONTEXT).setText("实现一项计划")}
            ui.click(page,"准备辨析问题")
            ui.main {val draft=page.findViewById<EditText>(TeacherView.ID_QUESTION).text.toString();check(draft.contains("implement")&&draft.contains("实现一项计划")&&calls.get()==1);page.finish()}
            test.waitForIdleSync();credentials.clear();page=open();ui.await("offline word page"){page.hasWindowFocus()}
            ui.main {check(ui.text(page.window.decorView).contains("这里是一条已保存的讲解"));check(page.findViewById<EditText>(TeacherView.ID_QUESTION).text.toString().contains("implement"));check(monitor.hits==0 && calls.get()==1)}
            return "PASS inline AI: real soft keyboard and visible send, one explicit phone API request across recreation, actual assessment/practice context, comparison drafts, offline answer, no TutorActivity navigation in ${app.resources.configuration.screenWidthDp}dp"
        } finally {ready.set(true);test.removeMonitor(monitor);ui.main {page.finish()};credentials.clear();ToolSettings.save(app,true);book.clearLearning()}
    }
}
