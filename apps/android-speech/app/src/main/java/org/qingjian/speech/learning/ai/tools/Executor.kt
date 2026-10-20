//! 白名单查询与受限自动学习操作；拒绝未知功能，按原问题验证朗读和练习意图。
package org.qingjian.speech.learning.ai.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.VocabularyEntry
import org.qingjian.speech.learning.ai.ToolCall
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind

class Executor(context: Context, private val automatic: Boolean = false) {
    private val app = context.applicationContext
    private val store = LearningStore.get(context)
    private val practice = PracticeStore(context)
    private val actions = ActionStore(context)

    fun execute(call: ToolCall, topic: String, question: Long): JSONObject = try {
        require(call.arguments.length <= 12000)
        val input = JSONObject(call.arguments)
        when(call.name) {
            "search_vocabulary" -> {
                Arguments.fields(input, setOf("query", "limit"))
                val query = Arguments.text(input, "query", 100, true)
                val limit = Arguments.integer(input.opt("limit"), 1, 10).toInt()
                JSONObject().put("status", "ok").put("words", JSONArray(store.words(query).take(limit).map { word(it) }))
                    .put("note", "计数表示接触和自评，不代表掌握；笔记由用户提供，可能有误。")
            }
            "get_learning_overview" -> {
                Arguments.fields(input, emptySet())
                val summary = JSONObject()
                for(kind in Kind.entries) {
                    val (total, correct) = practice.summary(kind)
                    summary.put(kind.name, JSONObject().put("attempts", total).put("correct", correct))
                }
                JSONObject().put("status", "ok").put("quizzes", summary)
                    .put("pronunciation",org.qingjian.speech.learning.assessment.Store(app).facts())
                    .put("mistakes", JSONArray(practice.mistakes().take(5).map {
                        JSONObject().put("word_id", it.wordId).put("english", it.english).put("user_note", it.meaning).put("kind", it.kind.name)
                    })).put("note", "只有真实作答与已保存的讯飞评测；缺失结果表示未评测，不是零分。没有错题不等于已掌握。")
            }
            "prepare_word_card" -> queued(topic, question, call.name, Arguments.card(input))
            "prepare_practice" -> {
                Arguments.fields(input, setOf("word_ids", "kind"))
                val ids = Arguments.ids(input)
                val kind = Kind.valueOf(Arguments.text(input, "kind", 20))
                require(ids.all { store.word(it) != null })
                queued(topic, question, call.name, JSONObject().put("word_ids", JSONArray(ids)).put("kind", kind.name))
            }
            "request_pronunciation" -> {
                Arguments.fields(input, setOf("word_id"))
                val id = Arguments.integer(input.opt("word_id"), 1, Long.MAX_VALUE)
                val selected = checkNotNull(store.word(id))
                queued(topic, question, call.name, JSONObject().put("word_id", id).put("english", selected.english))
            }
            else -> JSONObject().put("status", "denied").put("reason", "不支持此工具，没有执行任何操作")
        }
    } catch(_: Exception) {
        JSONObject().put("status", "invalid_arguments").put("reason", "参数格式、长度或词条不适用；未执行操作，请使用已有词条ID或缩小内容")
    }

    private fun queued(topic: String, question: Long, kind: String, data: JSONObject): JSONObject {
        if(automatic) {
            val original = checkNotNull(actions.questionText(topic, question))
            if(!IntentPolicy.allows(kind, original)) return JSONObject().put("status", "needs_user_request").put("note", "原问题未明确要求本项操作，或明确要求不要执行。请先回答问题，不要自行播放或出题。")
            if(kind == "prepare_word_card") {
                val exists = store.readableDatabase.rawQuery("SELECT 1 FROM words WHERE lookup=?", arrayOf(data.getString("english").lowercase(java.util.Locale.ROOT))).use { it.moveToFirst() }
                require(exists || actions.automaticCards(question) < org.qingjian.speech.learning.StudyPreferences(app).newWords.coerceAtMost(8)) { "已达到本轮新词上限" }
            }
        }
        val action = actions.queue(topic, question, kind, data)
        if(automatic) {
            actions.markAutomatic(action.id)
            if(action.state in listOf("dismissed", "undone")) return JSONObject().put("status", action.state).put("action_id", action.id)
            val result = JSONObject().put("action_id", action.id).put("kind", kind)
            when(kind) {
                "prepare_word_card" -> {
                    val wordId = actions.saveCard(action.id)
                    result.put("status", "saved").put("word_id", wordId).put("created", actions.get(action.id)?.createdWord == true)
                        .put("note", "已加入待学习或复用已有词条，原有非空笔记保留。新增词卡可在本机撤销，不代表已掌握。")
                }
                "prepare_practice" -> {
                    val questions = actions.practice(action.id)
                    result.put("status", "ready").put("count", questions.size).put("note", "题目已准备，App 将直接呈现。不要提前透露正确答案；实际作答后才计分。")
                }
                else -> result.put("status", if(action.state == "applied") "already_submitted" else "queued_for_playback")
                    .put("note", "App 前台将提交一次朗读请求。尚未确认播放完成，不能宣称已经听过。")
            }
            return result
        }
        return JSONObject().put("status", when(action.state) { "applied" -> "already_applied"; "dismissed" -> "dismissed"; else -> "pending_confirmation" })
            .put("action_id", action.id).put("kind", kind)
            .put("note", "只准备了本机操作卡。pending_confirmation 必须由用户点击，不能宣称已保存、播放或开始练习。")
    }

    private fun word(value: VocabularyEntry): JSONObject {
        val data = JSONObject().put("word_id", value.id).put("english", value.english)
            .put("user_note", value.meaning).put("received", value.received).put("completed_playbacks", value.played).put("self_reviews", value.reviewed)
        actions.approvedCard(value.id)?.let { card -> data.put("saved_example", card.optString("example")).put("saved_translation", card.optString("translation")) }
        return data
    }
}
