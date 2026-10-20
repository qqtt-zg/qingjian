//! 学习事件与可纠正档案；原有成绩在查询时合并，不重复导入计分。
package org.qingjian.speech.learning.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class Store(context:Context) {
    private val app=context.applicationContext
    val db get()=LearningStore.get(app).writableDatabase
    fun event(wordId:Long,english:String,kind:String,payload:JSONObject,key:String=UUID.randomUUID().toString()) {
        db.insertWithOnConflict("learning_events",null,ContentValues().apply {
            put("event_key",key);put("word_id",wordId);put("english",english);put("kind",kind);put("payload",payload.toString());put("created",System.currentTimeMillis())
        },SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun events():List<JSONObject> {
        val values=mutableListOf<JSONObject>()
        db.rawQuery("SELECT event_key,word_id,english,kind,payload,created FROM learning_events ORDER BY created,event_key",null).use {c->while(c.moveToNext())values+=JSONObject().put("key",c.getString(0)).put("word_id",c.getLong(1)).put("english",c.getString(2)).put("kind",c.getString(3)).put("data",JSONObject(c.getString(4))).put("created",c.getLong(5))}
        db.rawQuery("SELECT question_key,word_id,english,kind,answer,correct,created FROM quiz_attempts ORDER BY id",null).use {c->while(c.moveToNext())values+=JSONObject().put("key","quiz:"+c.getString(0)).put("word_id",c.getLong(1)).put("english",c.getString(2)).put("kind",c.getString(3)).put("data",JSONObject().put("answer",c.getString(4)).put("correct",c.getInt(5)==1).put("assisted",false)).put("created",c.getLong(6))}
        val assisted=db.rawQuery("SELECT snapshot FROM study_questions WHERE phase='EXAM' AND assisted=1",null).use {c->buildSet {while(c.moveToNext())add("quiz:"+JSONObject(c.getString(0)).getString("key"))}}
        values.filter {it.getString("key") in assisted}.forEach {it.getJSONObject("data").put("assisted",true)}
        val keys=values.map {it.getString("key")}.toSet()
        db.rawQuery("SELECT a.id,a.word_id,a.word_snapshot,a.state,a.result,a.created FROM oral_assessments a ORDER BY a.created",null).use {c->while(c.moveToNext())if("assessment:"+c.getString(0) !in keys)values+=JSONObject().put("key","oral:"+c.getString(0)).put("word_id",c.getLong(1)).put("english",c.getString(2)).put("kind","发音评测").put("data",JSONObject().put("state",c.getString(3)).put("result",c.getString(4))).put("created",c.getLong(5))}
        db.rawQuery("SELECT q.snapshot,q.answer,q.assisted,p.updated,q.plan_id FROM study_questions q JOIN study_plans p ON p.id=q.plan_id WHERE q.phase='PRACTICE' AND q.answer!=''",null).use {c->while(c.moveToNext()){
            val question=org.qingjian.speech.learning.quiz.Question.from(JSONObject(c.getString(0)));val key="daily:${c.getLong(4)}:${question.key}"
            if(key !in keys)values+=JSONObject().put("key",key).put("word_id",question.wordId).put("english",question.english).put("kind","每日练习").put("created",c.getLong(3)).put("data",JSONObject().put("answer",c.getString(1)).put("correct",question.correct(c.getString(1))).put("assisted",c.getInt(2)==1).put("time_note","旧每日练习按计划更新时间归档"))
        }}
        return values.sortedWith(compareBy<JSONObject> {it.getLong("created")}.thenBy {it.getString("key")})
    }
    fun on(date:LocalDate)=events().filter {day(it.getLong("created"))==date}
    fun memories():List<JSONObject> = db.rawQuery("SELECT id,body,source,corrected FROM learner_memory WHERE deleted=0 ORDER BY id",null).use {c->buildList {while(c.moveToNext())add(JSONObject().put("id",c.getString(0)).put("body",c.getString(1)).put("source",c.getString(2)).put("corrected",c.getInt(3)==1))}}
    fun revision()=db.rawQuery("SELECT revision FROM archive_meta WHERE id=1",null).use {it.moveToFirst();it.getLong(0)}
    fun edit(id:String,body:String,deleted:Boolean=false) {
        require(body.length<=3000)
        db.beginTransaction();try {
            db.execSQL("UPDATE learner_memory SET body=?,corrected=1,deleted=? WHERE id=?",arrayOf<Any>(body,if(deleted)1 else 0,id))
            db.execSQL("UPDATE archive_meta SET revision=revision+1 WHERE id=1");db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun saveAnalysis(body:String,expected:Long):Boolean {
        require(body.isNotBlank()&&body.length<=6000)
        db.beginTransaction();try {
            if(revision()!=expected)return false
            val old=db.rawQuery("SELECT corrected,deleted FROM learner_memory WHERE id='history'",null).use {if(it.moveToFirst())it.getInt(0)==1||it.getInt(1)==1 else false}
            if(!old)db.insertWithOnConflict("learner_memory",null,ContentValues().apply {put("id","history");put("body",body);put("source","手动分析全部学习历史，可能有误");put("corrected",0);put("deleted",0)},SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful();return !old
        }finally{db.endTransaction()}
    }
    fun context()=JSONObject().put("learner_memory",JSONArray(memories())).put("recent_learning_events",JSONArray(events().takeLast(20)))
    companion object {
        fun migrateAssessments(db:SQLiteDatabase){
            db.rawQuery("SELECT id,word_id,word_snapshot,state,result,created FROM oral_assessments",null).use {c->while(c.moveToNext())db.insertWithOnConflict("learning_events",null,ContentValues().apply {put("event_key","assessment:"+c.getString(0));put("word_id",c.getLong(1));put("english",c.getString(2));put("kind","发音评测");put("payload",JSONObject().put("state",c.getString(3)).put("result",c.getString(4)).toString());put("created",c.getLong(5))},SQLiteDatabase.CONFLICT_IGNORE)}
        }
        fun day(time:Long):LocalDate=java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
        fun create(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE learning_events (event_key TEXT PRIMARY KEY,word_id INTEGER NOT NULL,english TEXT NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE independent_exams (id TEXT PRIMARY KEY,payload TEXT NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE learner_memory (id TEXT PRIMARY KEY,body TEXT NOT NULL,source TEXT NOT NULL,corrected INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE archive_meta (id INTEGER PRIMARY KEY,revision INTEGER NOT NULL)");db.execSQL("INSERT INTO archive_meta VALUES(1,0)")
            db.execSQL("CREATE TABLE ai_usage (id INTEGER PRIMARY KEY AUTOINCREMENT,task TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE history_analysis (id INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE desktop_sources (word_id INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
        }
    }
}
