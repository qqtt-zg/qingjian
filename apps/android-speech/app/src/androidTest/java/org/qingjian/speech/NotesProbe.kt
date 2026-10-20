//! 真实词条页验证手机 AI 独立补全、Koog提案提交、并发保护、取消和离线资料。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.ToolSettings
import org.qingjian.speech.learning.notes.Store
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object NotesProbe {
    fun run(test: Instrumentation): String {
        val app=test.targetContext;val ui=ProbeUi(test);val book=LearningStore.get(app)
        val storeResult=NotesStoreProbe.run(app);val notes=Store(app);val credentials=Credentials(app)
        val prefs=app.getSharedPreferences("speech",0);val previous=prefs.getString("url",null)
        prefs.edit().remove("url").commit();credentials.clear();ToolSettings.save(app,false)
        val profile=Profile("https://mobile-notes.invalid/v1","phone-model","phone-fake-key")
        val calls=AtomicInteger();val ready=AtomicBoolean(true);val status=AtomicInteger(200)
        val malformed=AtomicBoolean();val failFollowup=AtomicBoolean();val sawKoog=AtomicBoolean()
        URL.setURLStreamHandlerFactory {scheme -> if(scheme!="https")null else object:URLStreamHandler() {
            override fun openConnection(url:URL):URLConnection {
                check(url.host=="mobile-notes.invalid" && url.path=="/v1/chat/completions")
                val sent=ByteArrayOutputStream();var body=JSONObject();val stopped=AtomicBoolean()
                return object:HttpURLConnection(url) {
                    override fun connect()=Unit
                    override fun disconnect() {stopped.set(true)}
                    override fun usingProxy()=false
                    override fun getOutputStream()=sent
                    override fun getResponseCode():Int {
                        calls.incrementAndGet()
                        check(!instanceFollowRedirects && getRequestProperty("Authorization")=="Bearer phone-fake-key")
                        body=JSONObject(sent.toString("UTF-8"))
                        check(body.getString("model")=="phone-model" && !body.toString().contains("Bearer"))
                        check(!body.toString().contains("trycloudflare") && !body.toString().contains("配对凭证"))
                        sawKoog.set(sawKoog.get() || Thread.currentThread().stackTrace.any {it.className.startsWith("ai.koog.")})
                        while(!ready.get()) {if(stopped.get())throw IOException("cancelled fixture");Thread.sleep(20)}
                        val followup=body.getJSONArray("messages").toString().contains("\"role\":\"tool\"")
                        return if(followup && failFollowup.get())500 else status.get()
                    }
                    override fun getInputStream():ByteArrayInputStream {
                        val tools=body.has("tools")
                        val followup=body.getJSONArray("messages").toString().contains("\"role\":\"tool\"")
                        val result=when {
                            tools && !followup -> NotesFixture.tool()
                            tools -> NotesFixture.answer("完整资料已准备，等待手机保存。")
                            malformed.get() -> NotesFixture.answer(NotesFixture.pack().apply {remove("usage")}.toString())
                            else -> NotesFixture.answer(NotesFixture.pack().toString())
                        }
                        return ByteArrayInputStream(result.toByteArray())
                    }
                }
            }
        } }
        val id=book.addWord("control")
        fun open()=test.startActivitySync(Intent(app,WordStudyActivity::class.java).putExtra("word_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WordStudyActivity
        var page=open()
        fun text()=ui.text(page.window.decorView)
        fun waitFor(value:String)=ui.await(value,20000){text().contains(value)}
        fun generate()=ui.main {check(page.findViewById<android.widget.Button>(WordStudyActivity.ID_NOTE).performClick())}
        fun capture(name:String) {
            test.waitForIdleSync();test.uiAutomation.waitForIdle(250,5000)
            val bitmap=checkNotNull(test.uiAutomation.takeScreenshot());val dir=File(app.filesDir,"ui-review").apply {mkdirs()}
            File(dir,"mobile-materials-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        try {
            waitFor("AI 补全学习资料");check(calls.get()==0);generate();waitFor("请先在手机设置中配置 AI")
            check(calls.get()==0 && ui.button(page.window.decorView,"配置手机 AI")!=null)
            credentials.save(profile);status.set(401);generate();waitFor("认证失败");check(calls.get()==1)
            status.set(200);ready.set(false)
            val draft=page.findViewById<android.widget.EditText>(WordStudyActivity.ID_OTHER)
            ui.main {draft.setText("manage")}
            generate();waitFor("等待模型回答")
            ui.main {check(!page.findViewById<android.widget.Button>(WordStudyActivity.ID_NOTE).isEnabled)}
            val count=calls.get();val monitor=test.addMonitor(WordStudyActivity::class.java.name,null,false)
            ui.main {page.recreate()}
            page=test.waitForMonitorWithTimeout(monitor,15000) as? WordStudyActivity ?: error("recreate failed")
            test.removeMonitor(monitor);waitFor("等待模型回答");check(calls.get()==count)
            ui.main {check(page.findViewById<android.widget.EditText>(WordStudyActivity.ID_OTHER).text.toString()=="manage")}
            ready.set(true);waitFor("已补全 · 手机 AI")
            check(notes.complete(id) && text().contains("control costs") && text().contains("作及物动词时直接接宾语"))
            check(!text().contains("电脑云服务") && !text().contains("还没有讲解") && book.messages("word:$id").isEmpty())
            capture("saved")
            ui.click(page,"撤销本次补全");waitFor("已撤销本次补全");check(notes.card(id)==null && book.word(id)!!.meaning.isEmpty())

            book.setMeaning(id,"控制（手工笔记）");generate();waitFor("已补全 · 手机 AI")
            check(book.word(id)!!.meaning=="控制（手工笔记）" && notes.complete(id))
            ui.click(page,"撤销本次补全");check(book.word(id)!!.meaning=="控制（手工笔记）" && notes.card(id)==null)
            ready.set(false);generate();waitFor("等待模型回答")
            book.setMeaning(id,"");book.setMeaning(id,"新的手工笔记")
            ready.set(true);waitFor("本次结果未覆盖");check(notes.card(id)==null && book.word(id)!!.meaning=="新的手工笔记")

            ready.set(false);generate();waitFor("等待模型回答")
            credentials.save(Profile("https://changed.invalid/v1","different","other-fake-key"))
            ready.set(true);waitFor("手机 AI 配置已更换");check(notes.card(id)==null);credentials.save(profile)
            malformed.set(true);generate();waitFor("整包未保存");check(notes.card(id)==null)
            val failedCalls=calls.get();Thread.sleep(500);check(calls.get()==failedCalls);malformed.set(false)

            ToolSettings.save(app,true);failFollowup.set(true);generate();waitFor("HTTP 500")
            check(notes.card(id)==null && book.word(id)!!.meaning=="新的手工笔记")
            failFollowup.set(false);ready.set(false);generate();waitFor("等待模型回答")
            ui.click(page,"取消补全");ready.set(true);waitFor("本次学习资料未保存");check(notes.card(id)==null)
            generate();waitFor("已补全 · 手机 AI");check(sawKoog.get() && notes.complete(id))
            check(book.word(id)!!.played==0 && book.word(id)!!.reviewed==0 && book.word(id)!!.received==0)
            val stableCalls=calls.get();ui.main {page.finish()};test.waitForIdleSync();credentials.clear()
            page=open();waitFor("You can control the volume.");check(text().contains("control costs") && text().contains("作及物动词时直接接宾语"))
            check(calls.get()==stableCalls && page.findViewById<android.widget.Button>(WordStudyActivity.ID_NOTE)==null)
            capture("offline")
            return "$storeResult\nPASS mobile AI word materials: zero PC/pairing dependence, phone key/model only, Koog and text mode, whole-pack save, rollback on failed followup, rotation/draft retention, edit/config/cancel guards and offline cards"
        } finally {
            ready.set(true);ui.main {if(!page.isFinishing)page.finish()}
            credentials.clear();ToolSettings.save(app,true)
            prefs.edit().apply {if(previous==null)remove("url") else putString("url",previous)}.commit();book.clearLearning()
        }
    }
}
