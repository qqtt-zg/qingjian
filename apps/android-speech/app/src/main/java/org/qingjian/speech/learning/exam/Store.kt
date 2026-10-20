//! 独立考试的固定题目快照、草稿和交卷；唯一事件键避免重复交卷计分。
package org.qingjian.speech.learning.exam

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.quiz.Question
import java.time.LocalDate
import java.util.UUID

class Store(context:Context) {
    private val app=context.applicationContext
    private val book=LearningStore.get(app)
    private val db get()=book.writableDatabase
    fun get(id:String):JSONObject?=db.rawQuery("SELECT payload FROM independent_exams WHERE id=?",arrayOf(id)).use {if(it.moveToFirst())JSONObject(it.getString(0)) else null}
    fun latest():JSONObject?=db.rawQuery("SELECT payload FROM independent_exams ORDER BY created DESC,rowid DESC LIMIT 1",null).use {if(it.moveToFirst())JSONObject(it.getString(0)) else null}
    fun pool(scope:String)=book.allWords().filter {it.meaning.isNotBlank()}.filter {word->when(scope){
        "今日接触"->org.qingjian.speech.learning.archive.Store.day(word.lastSeen)==LocalDate.now()
        "本周接触"->org.qingjian.speech.learning.archive.Store.day(word.lastSeen)>=LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        "待巩固"->org.qingjian.speech.learning.archive.Store(app).events().any {it.optLong("word_id")==word.id&&it.getJSONObject("data").has("correct")&&!it.getJSONObject("data").getBoolean("correct")}
        else->true
    }}
    fun create(scope:String,count:Int,selected:Set<Long>?=null):JSONObject {
        db.beginTransaction();try {
        latest()?.let {require(it.optBoolean("graded")){"请先继续或交卷，未完成考试不会被覆盖"}}
        val words=pool(scope).filter {selected==null||it.id in selected}.take(count.coerceIn(1,30));require(words.isNotEmpty()){"所选范围没有可出题的中文笔记"}
        val id=UUID.randomUUID().toString()
        val q=JSONArray(words.mapIndexed {index,w->
            val example=org.qingjian.speech.learning.ai.tools.ActionStore(app).approvedCard(w.id)?.optString("example").orEmpty()
            val pattern=Regex("(?i)(?<![A-Za-z])"+Regex.escape(w.english)+"(?![A-Za-z])")
            val gap=if(pattern.findAll(example).count()==1)pattern.replace(example,"____") else ""
            JSONObject().put("word_id",w.id).put("english",w.english).put("meaning",w.meaning).put("mode",if(index%3==1)"听写" else if(index%3==2&&gap.isNotBlank())"语境填词" else "看义拼写").put("prompt",gap).put("answer","").put("replays",0)
        })
        val data=JSONObject().put("id",id).put("scope",scope).put("questions",q).put("index",0).put("graded",false)
        db.insertOrThrow("independent_exams",null,ContentValues().apply {put("id",id);put("payload",data.toString());put("created",System.currentTimeMillis())});db.setTransactionSuccessful();return data
        }finally{db.endTransaction()}
    }
    fun save(data:JSONObject){
        require(!checkNotNull(get(data.getString("id"))).optBoolean("graded")){"已交卷的结果不可修改"}
        db.update("independent_exams",ContentValues().apply {put("payload",data.toString())},"id=?",arrayOf(data.getString("id")))
    }
    fun submit(id:String):JSONObject {
        db.beginTransaction();try {
            val data=checkNotNull(get(id));if(data.optBoolean("graded"))return data
            val questions=data.getJSONArray("questions");var correct=0
            for(i in 0 until questions.length()){
                val q=questions.getJSONObject(i);val valid=Question.normalize(q.getString("answer"))==Question.normalize(q.getString("english"));if(valid)correct++
                q.put("correct",valid)
                org.qingjian.speech.learning.archive.Store(app).event(q.getLong("word_id"),q.getString("english"),"独立考试",JSONObject().put("answer",q.getString("answer")).put("correct",valid).put("assisted",false).put("mode",q.getString("mode")),"exam:$id:$i")
            }
            data.put("graded",true).put("correct",correct);save(data);db.setTransactionSuccessful();return data
        }finally{db.endTransaction()}
    }
}
