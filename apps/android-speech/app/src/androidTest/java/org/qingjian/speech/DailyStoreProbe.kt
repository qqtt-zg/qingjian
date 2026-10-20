//! 每日计划的实际 SQLite 执行规则：重建、幂等、独立成绩、自评与陈旧建议隔离。
package org.qingjian.speech

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.daily.PlanStore

object DailyStoreProbe {
    fun material(id: Long, en: String, meaning: String, example: String, purpose: String) = JSONObject()
        .put("word_id", id).put("english", en).put("meaning", meaning).put("example", example).put("translation", "测试例句中文") .put("purpose", purpose)

    fun proposal(control: Long, help: Long) = JSONObject().put("title", "日常控制与帮助").put("reason", "依据本机词条安排少量学习")
        .put("items", JSONArray().put(material(control, "control", "控制", "You control the volume.", "LEARN"))
            .put(material(help, "help", "帮助", "Can you help me?", "REVIEW")))

    fun run(app: Context): String {
        val book = LearningStore.get(app); book.clearLearning()
        val control = book.addWord("control"); book.setMeaning(control, "控制")
        val help = book.addWord("help"); book.setMeaning(help, "帮助")
        val store = PlanStore(app)
        val bad = proposal(control, help); bad.getJSONArray("items").getJSONObject(0).put("word_id", Long.MAX_VALUE)
        check(runCatching { store.create(bad) }.isFailure && store.current() == null)
        var plan = store.create(proposal(control, help))
        check(runCatching { store.create(proposal(control, help)) }.isFailure)
        check(book.word(control)!!.meaning == "控制")
        store.markLearned(plan.id, store.items(plan.id).first { it.purpose == "LEARN" }.id)
        check(store.get(plan.id)!!.phase == "PRACTICE")
        var index = 0
        while(store.get(plan.id)!!.phase == "PRACTICE") {
            plan = store.get(plan.id)!!
            val q = store.questions(plan.id, "PRACTICE")[plan.cursor]
            if(index++ == 0) store.assist(plan.id)
            store.draft(plan.id, q.question.english)
            check(PlanStore(app).questions(plan.id, "PRACTICE")[plan.cursor].draft == q.question.english)
            store.submit(plan.id); store.submit(plan.id); store.advance(plan.id)
        }
        check(book.readableDatabase.rawQuery("SELECT COUNT(*) FROM quiz_attempts", null).use { it.moveToFirst(); it.getInt(0) } == 0)
        plan = store.get(plan.id)!!; check(plan.phase == "ADAPT")
        val revision = plan.revision
        val adjustment = JSONObject().put("reason", "针对刚才练习继续检验").put("kind", "ENGLISH_CHOICE").put("count", 3).put("focus_word_ids", JSONArray(listOf(control)))
        check(runCatching { store.adjustment(plan.id, revision - 1, adjustment) }.isFailure)
        val light=JSONObject(adjustment.toString()).put("count",1)
        val lightPlan=store.adjustment(plan.id, revision, light,"今天轻松一点，后续只做一题")
        check(lightPlan.questionLimit==1&&lightPlan.userInstruction.contains("一题"))
        check(runCatching {store.adjustment(plan.id,lightPlan.revision,adjustment)}.isFailure)
        store.adjustment(plan.id, lightPlan.revision, adjustment,"现在可以做三题"); store.advance(plan.id)
        var exam = 0
        while(store.get(plan.id)!!.phase == "EXAM") {
            plan = store.get(plan.id)!!
            val q = store.questions(plan.id, "EXAM")[plan.cursor]
            if(q.listening) { store.assist(plan.id); store.replay(plan.id) }
            val value = if(exam++ == 2) "wrong" else if(q.question.kind.name == "CHOICE") q.question.meaning else q.question.english
            store.draft(plan.id, value); store.submit(plan.id); store.submit(plan.id); store.advance(plan.id)
        }
        val result = store.results(plan.id)
        check(result.getInt("correct") == 2 && result.getInt("independent_correct") == 1 && result.getInt("assisted") == 1)
        check(book.readableDatabase.rawQuery("SELECT COUNT(*) FROM quiz_attempts", null).use { it.moveToFirst(); it.getInt(0) } == 3)
        check(result.getJSONArray("practice_results").length() > 0)
        val now=System.currentTimeMillis()
        org.qingjian.speech.learning.daily.ReviewSchedule.record(book.writableDatabase,control,"SPELLING",true,now)
        org.qingjian.speech.learning.daily.ReviewSchedule.record(book.writableDatabase,control,"SPELLING",true,now+1000)
        check(book.readableDatabase.rawQuery("SELECT streak FROM study_reviews WHERE word_id=? AND skill='SPELLING'",arrayOf(control.toString())).use {it.moveToFirst();it.getInt(0)}==1)
        store.advance(plan.id); store.rate(plan.id, false)
        check(store.get(plan.id)!!.phase == "SUMMARY" && book.word(help)!!.reviewed == 1)
        check(book.readableDatabase.rawQuery("SELECT streak FROM study_reviews WHERE word_id=? AND skill='SELF'", arrayOf(help.toString())).use { it.moveToFirst(); it.getInt(0) } == 0)
        plan = store.get(plan.id)!!
        store.reflection(plan.id, plan.revision, JSONObject().put("suggestion", "下一次继续巩固 control 的拼写。"))
        store.advance(plan.id); check(store.current()!!.phase == "DONE")
        plan = store.get(plan.id)!!; book.clearLearning()
        check(runCatching { store.reflection(plan.id, plan.revision, JSONObject().put("suggestion", "晚到建议")) }.isFailure && store.get(plan.id) == null)
        return "PASS daily store: snapshots/resume, invalid IDs, existing notes, practice/exam separation, idempotent scoring, assistance, self review, revision checks and clear prevents resurrection"
    }
}
