//! 隔离受控接口验证真实 Koog 对日计划、调整和回顾的三段调用，取消不提交提案。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.ToolSettings
import org.qingjian.speech.learning.daily.Agent
import org.qingjian.speech.learning.daily.PlanStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

object DailyAgentProbe {
    fun response(id: String, name: String, args: JSONObject): String {
        val function = JSONObject().put("name", name).put("arguments", args.toString())
        val call = JSONObject().put("id", id).put("type", "function").put("function", function)
        val message = JSONObject().put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(call))
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))).toString()
    }

    fun run(test: Instrumentation): String {
        val app=test.targetContext; val book=LearningStore.get(app); book.clearLearning(); ToolSettings.save(app,true)
        val control=book.addWord("control");book.setMeaning(control,"控制")
        val help=book.addWord("help");book.setMeaning(help,"帮助")
        var responses=emptyList<String>();var step=0;var sawKoog=false;var cancelled=false;var cancelAt=-1;var clearAt=-1
        val bodies=mutableListOf<JSONObject>()
        URL.setURLStreamHandlerFactory { protocol -> if(protocol!="https") null else object:URLStreamHandler(){
            override fun openConnection(url:URL):URLConnection {
                check(url.host=="daily-agent.invalid")
                val sent=ByteArrayOutputStream();var index=0
                return object:HttpURLConnection(url){
                    override fun connect()=Unit
                    override fun disconnect()=Unit
                    override fun usingProxy()=false
                    override fun getOutputStream()=sent
                    override fun getResponseCode():Int {
                        sawKoog=sawKoog||Thread.currentThread().stackTrace.any { it.className.startsWith("ai.koog.") }
                        check(getRequestProperty("Authorization")=="Bearer fake-key"&&!instanceFollowRedirects)
                        index=step++;bodies+=JSONObject(sent.toString("UTF-8"));return 200
                    }
                    override fun getInputStream():ByteArrayInputStream {
                        if(index==cancelAt)cancelled=true
                        if(index==clearAt)book.clearLearning()
                        return ByteArrayInputStream(responses[index].toByteArray())
                    }
                }
            }
        } }
        fun configure(name:String, proposal:JSONObject){step=0;bodies.clear();responses=listOf(response("read","get_study_context",JSONObject()),response("propose",name,proposal),"""{"choices":[{"message":{"content":"本轮提案已准备，以 App 最终提交为准。"}}]}""")}
        val plans=PlanStore(app);val agent=Agent(app);val profile=Profile("https://daily-agent.invalid/v1","fake-model","fake-key")
        configure("prepare_study_plan",DailyStoreProbe.proposal(control,help))
        var plan=agent.run("CREATE",0,"安排今天学习",Client(),profile,{cancelled},{})
        check(sawKoog&&step==3&&bodies.first().getJSONArray("tools").length()==1&&bodies.last().getString("tool_choice")=="none")
        check(bodies[1].toString().contains("available_words"))
        plans.markLearned(plan.id,plans.items(plan.id).first {it.purpose=="LEARN"}.id)
        while(plans.get(plan.id)!!.phase=="PRACTICE"){
            val p=plans.get(plan.id)!!;val q=plans.questions(p.id,"PRACTICE")[p.cursor]
            plans.assist(p.id);plans.draft(p.id,q.question.english);plans.submit(p.id);plans.advance(p.id)
        }
        configure("prepare_study_adjustment",JSONObject().put("reason","练习使用了提示，先做两题词义检查").put("focus_word_ids",JSONArray(listOf(control))).put("kind","ENGLISH_CHOICE").put("count",2))
        plan=agent.run("ADAPT",plan.id,"根据真实练习调整",Client(),profile,{false},{})
        check(plan.phase=="EXAM_INTRO"&&plans.questions(plan.id,"EXAM").size==2&&bodies[1].toString().contains("practice_results"))
        plans.advance(plan.id)
        while(plans.get(plan.id)!!.phase=="EXAM"){
            val p=plans.get(plan.id)!!;val q=plans.questions(p.id,"EXAM")[p.cursor]
            if(q.listening)plans.assist(p.id)
            plans.draft(p.id,if(q.question.kind.name=="CHOICE")q.question.meaning else q.question.english);plans.submit(p.id);plans.advance(p.id)
        }
        plans.advance(plan.id);plans.rate(plan.id,false)
        configure("prepare_study_reflection",JSONObject().put("suggestion","本轮练习用了提示，下一次优先巩固 control；help 自评还没想起来。"))
        plan=agent.run("SUMMARY",plan.id,"回顾真实结果",Client(),profile,{false},{})
        check(plan.reflection.contains("control")&&bodies[1].toString().contains("independent_correct")&&plans.results(plan.id).getInt("independent_correct")==1)
        val revision=plan.revision
        configure("prepare_study_reflection",JSONObject().put("suggestion","不应保存的回顾"));cancelAt=2
        check(runCatching {agent.run("SUMMARY",plan.id,"测试取消",Client(),profile,{cancelled},{})}.isFailure)
        check(plans.get(plan.id)!!.revision==revision)
        cancelAt=-1;cancelled=false;book.clearLearning()
        val late=JSONObject().put("title","晚到计划").put("reason","不应恢复清空的数据").put("items",JSONArray().put(DailyStoreProbe.material(0,"need","需要","I need help.","LEARN")))
        configure("prepare_study_plan",late);clearAt=2
        check(runCatching {agent.run("CREATE",0,"测试清空",Client(),profile,{false},{})}.isFailure)
        check(plans.current()==null&&book.words().isEmpty())
        return "PASS daily Koog: actual graph, scoped tools, fact read before proposal, durable plan, outcome-driven adjustment, actual result reflection, final cancellation and data-clear epoch"
    }
}
