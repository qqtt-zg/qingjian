//! 每日学习真实原生流程：Koog 计划、即时练习、调整、小测、复习和回顾。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.ToolSettings
import org.qingjian.speech.learning.daily.PlanStore
import org.qingjian.speech.learning.daily.StudyActivity
import org.qingjian.speech.learning.quiz.Kind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object DailyUiProbe {
    fun run(test: Instrumentation): String {
        val app=test.targetContext;val ui=ProbeUi(test);val book=LearningStore.get(app);book.clearLearning()
        val control=book.addWord("control");book.setMeaning(control,"控制")
        val help=book.addWord("help");book.setMeaning(help,"帮助")
        Credentials(app).save(Profile("https://daily-ui.invalid/v1","fake-model","fake-key"));ToolSettings.save(app,true)
        fun capture(name:String) {
            test.waitForIdleSync();test.uiAutomation.waitForIdle(250,5000)
            val image=checkNotNull(test.uiAutomation.takeScreenshot());val dir=File(app.filesDir,"ui-review").apply {mkdirs()}
            File(dir,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
        val plans=PlanStore(app);val calls=AtomicInteger();var reflectionFacts="";var sawKoog=false
        URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https")null else object:URLStreamHandler(){
            override fun openConnection(url:URL):URLConnection {
                check(url.host=="daily-ui.invalid");val sent=ByteArrayOutputStream();var body=JSONObject()
                return object:HttpURLConnection(url){
                    override fun connect()=Unit
                    override fun disconnect()=Unit
                    override fun usingProxy()=false
                    override fun getOutputStream()=sent
                    override fun getResponseCode():Int {
                        sawKoog=sawKoog||Thread.currentThread().stackTrace.any {it.className.startsWith("ai.koog.")}
                        body=JSONObject(sent.toString("UTF-8"));calls.incrementAndGet();return 200
                    }
                    override fun getInputStream():ByteArrayInputStream {
                        val defs=body.getJSONArray("tools");val name=if(defs.length()>1)defs.getJSONObject(1).getJSONObject("function").getString("name") else "get_study_context"
                        val response=when {
                            body.getString("tool_choice")=="none" -> """{"choices":[{"message":{"content":"提案已准备。"}}]}"""
                            !body.getJSONArray("messages").toString().contains("\"role\":\"tool\"") -> DailyAgentProbe.response("facts","get_study_context",JSONObject())
                            else -> {
                                val value=when(name){
                                    "prepare_study_plan" -> DailyStoreProbe.proposal(control,help).apply {
                                        put("title","调节音量与管理团队")
                                        getJSONArray("items").put(DailyStoreProbe.material(0,"manage","管理","I manage a team.","LEARN"))
                                    }
                                    "prepare_study_adjustment" -> JSONObject().put("reason","练习借助了提示，后续先做两道词义检查。").put("kind","ENGLISH_CHOICE").put("count",2).put("focus_word_ids",JSONArray(listOf(control)))
                                    else -> {reflectionFacts=body.getJSONArray("messages").toString();JSONObject().put("suggestion","本轮有一次文字辅助。下次继续巩固 control；help 的自评还需练习。")}
                                }
                                DailyAgentProbe.response("proposal",name,value)
                            }
                        }
                        return ByteArrayInputStream(response.toByteArray())
                    }
                }
            }
        } }
        val home=test.startActivitySync(Intent(app,LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("daily home") {home.hasWindowFocus()&&ui.button(home.window.decorView,"安排今天学习")!=null}
        val monitor=test.addMonitor(StudyActivity::class.java.name,null,false)
        capture("daily-home")
        ui.click(home,"安排今天学习")
        var page=test.waitForMonitorWithTimeout(monitor,15000) as? StudyActivity ?: error("每日页面未打开")
        test.removeMonitor(monitor)
        ui.await("real generated plan",25000){!plans.current().let {it==null||it.phase!="LEARN"}&&ui.button(page.window.decorView,"下一词")!=null}
        check(sawKoog&&calls.get()==3&&book.words("manage").isNotEmpty())
        val id=plans.current()!!.id
        capture("daily-lesson")
        ui.click(page,"下一词");ui.click(page,"开始练习")
        var first=true
        while(plans.get(id)!!.phase=="PRACTICE") {
            val p=plans.get(id)!!;val q=plans.questions(id,"PRACTICE")[p.cursor]
            if(first){ui.click(page,"给我一点提示");first=false}
            ui.click(page,"每日选项：${q.question.english}")
            check(ui.text(page.window.decorView).contains("这次答对了"))
            ui.click(page,"继续")
        }
        ui.await("agent adjustment",25000){plans.get(id)!!.phase=="EXAM_INTRO"&&ui.button(page.window.decorView,"开始独立小测")!=null}
        check(calls.get()==6&&plans.questions(id,"EXAM").size==2)
        capture("daily-adjustment")
        ui.click(page,"开始独立小测")
        var p=plans.get(id)!!;var q=plans.questions(id,"EXAM")[p.cursor]
        ui.click(page,"每日选项：${q.question.english}")
        check(!ui.text(page.window.decorView).contains("本题答对"))
        val before=calls.get();val rotation=test.addMonitor(StudyActivity::class.java.name,null,false)
        ui.main {page.recreate()}
        page=test.waitForMonitorWithTimeout(rotation,15000) as? StudyActivity ?: error("重建未完成")
        test.removeMonitor(rotation);ui.await("rotation restored"){page.hasWindowFocus()&&ui.button(page.window.decorView,"下一题")?.isEnabled==true}
        check(calls.get()==before&&plans.results(id).getInt("submitted")==0)
        ui.click(page,"下一题")
        p=plans.get(id)!!;q=plans.questions(id,"EXAM")[p.cursor];check(q.listening)
        check(!ui.text(page.window.decorView).contains("文字辅助："))
        ui.click(page,"音频不可用，显示文字辅助")
        ui.click(page,"每日选项：${q.question.meaning}");ui.click(page,"完成小测")
        check(plans.get(id)!!.phase=="RESULT"&&plans.results(id).getInt("independent_correct")==1)
        ui.click(page,"接着复习旧词");ui.click(page,"查看答案");ui.click(page,"还没想起来")
        ui.await("agent reflection",25000){plans.get(id)!!.reflection.isNotBlank()&&ui.button(page.window.decorView,"今天先到这里")!=null}
        check(calls.get()==9&&reflectionFacts.contains("independent_correct")&&reflectionFacts.contains("self_rating"))
        val result=plans.results(id);check(result.getInt("correct")==2&&result.getInt("assisted")==1)
        test.waitForIdleSync();test.uiAutomation.waitForIdle(300,5000)
        val image=checkNotNull(test.uiAutomation.takeScreenshot());val dir=File(app.filesDir,"ui-review").apply {mkdirs()}
        File(dir,"daily-summary.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        ui.click(page,"今天先到这里");ui.await("home summary") {home.hasWindowFocus()&&ui.button(home.window.decorView,"查看今日小结")!=null}
        check(plans.current()!!.phase=="DONE");ui.main {home.finish()}
        return "PASS daily native UI: real Koog plan/adjustment/reflection, new material, fixed actions, practice feedback, withheld exam answer, rotation preserves pending choice/no request replay, assistance, self review and completion"
    }
}
