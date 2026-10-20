//! Agent 的有界事实视图；已到期与尚无复习记录的候选分开，不制造学习经历。
package org.qingjian.speech.learning.daily

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.StudyPreferences

class Facts(private val app: Context) {
    fun snapshot(id: Long): JSONObject {
        val book = LearningStore.get(app); val db = book.readableDatabase; val plans = PlanStore(app)
        val result = JSONObject().put("today", PlanStore.today()).put("preferences", StudyPreferences(app).context())
        result.put("learning_archive",org.qingjian.speech.learning.archive.Store(app).context())
        result.put("pronunciation",org.qingjian.speech.learning.assessment.Store(app).facts())
        result.put("available_words", JSONArray(book.words().take(40).map { JSONObject().put("word_id", it.id).put("english", it.english).put("meaning", it.meaning).put("received", it.received).put("plays", it.played).put("self_reviews", it.reviewed) }))
        result.put("due_reviews", db.rawQuery("SELECT r.word_id,r.skill,r.streak,r.next_due,w.english,w.meaning FROM study_reviews r JOIN words w ON w.id=r.word_id WHERE r.next_due<=? ORDER BY r.next_due LIMIT 15", arrayOf(System.currentTimeMillis().toString())).use { c ->
            JSONArray().apply { while(c.moveToNext()) put(JSONObject().put("word_id", c.getLong(0)).put("skill", c.getString(1)).put("streak", c.getInt(2)).put("due", c.getLong(3)).put("english", c.getString(4)).put("meaning",c.getString(5))) }
        })
        result.put("recent_attempts", db.rawQuery("SELECT a.word_id,a.kind,a.correct,a.created FROM quiz_attempts a JOIN words w ON w.id=a.word_id WHERE a.created>=? ORDER BY a.id DESC LIMIT 30", arrayOf((System.currentTimeMillis()-14*86400000L).toString())).use { c ->
            JSONArray().apply { while(c.moveToNext()) put(JSONObject().put("word_id", c.getLong(0)).put("kind", c.getString(1)).put("correct", c.getInt(2)==1).put("created", c.getLong(3))) }
        })
        result.put("recent_daily_results",db.rawQuery("SELECT id,day FROM study_plans ORDER BY id DESC LIMIT 7",null).use {c->
            JSONArray().apply {while(c.moveToNext())put(JSONObject().put("plan_id",c.getLong(0)).put("day",c.getString(1)).put("results",plans.results(c.getLong(0))))}
        })
        plans.get(id)?.let { p ->
            result.put("plan", JSONObject().put("id", p.id).put("title", p.title).put("phase", p.phase).put("revision", p.revision)
                .put("previous_adjustment",p.adjustmentReason).put("user_instruction",p.userInstruction).put("question_limit",p.questionLimit)
                .put("items", JSONArray(plans.items(id).map { JSONObject().put("word_id", it.wordId).put("english", it.english).put("meaning", it.meaning).put("example",it.example).put("purpose", it.purpose).put("learned", it.learned) })))
            result.put("results", plans.results(id))
        }
        return result.put("note", "计数表示接触；不等于掌握。没有复习日期的词不是已到期。成绩和计划进度由App维护。")
    }
}
