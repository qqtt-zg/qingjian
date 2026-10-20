//! 每日计划与执行状态；模型提案校验后原子保存，已提交题目与进度不被调整覆盖。
package org.qingjian.speech.learning.daily

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.StudyPreferences
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Builder
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.Question
import org.qingjian.speech.learning.ai.tools.Arguments
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlanStore(private val app: Context) {
    private val store = LearningStore.get(app)
    private val db get() = store.writableDatabase
    private val columns = "id,day,title,reason,phase,cursor,revision,adjustment,reflection"

    fun current(): Plan? = query("phase!='DONE' ORDER BY id DESC LIMIT 1") ?: query("day=? ORDER BY id DESC LIMIT 1", arrayOf(today()))
    fun get(id: Long): Plan? = query("id=?", arrayOf(id.toString()))
    private fun query(where: String, args: Array<String>? = null): Plan? = db.rawQuery("SELECT $columns FROM study_plans WHERE $where", args).use { c ->
        if(!c.moveToFirst()) null else Plan(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6), c.getString(7), c.getString(8))
    }

    fun items(id: Long): List<PlanItem> = db.rawQuery("SELECT id,word_id,english,meaning,example,translation,purpose,learned,rating FROM study_items WHERE plan_id=? ORDER BY id", arrayOf(id.toString())).use { c ->
        buildList { while(c.moveToNext()) add(PlanItem(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1, c.getString(8))) }
    }

    fun questions(id: Long, phase: String): List<StudyQuestion> = db.rawQuery("SELECT snapshot,listening,draft,assisted,answer,replays FROM study_questions WHERE plan_id=? AND phase=? ORDER BY position", arrayOf(id.toString(), phase)).use { c ->
        buildList { while(c.moveToNext()) add(StudyQuestion(Question.from(JSONObject(c.getString(0))), c.getInt(1) == 1, c.getString(2), c.getInt(3) == 1, c.getString(4), c.getInt(5))) }
    }

    fun epoch(): Long = db.rawQuery("SELECT epoch FROM study_meta WHERE id=1", null).use { it.moveToFirst(); it.getLong(0) }

    fun create(proposal: JSONObject, expectedEpoch: Long = epoch()): Plan = transaction {
        require(epoch() == expectedEpoch) { "资料已清空，本次计划未保存" }
        require(current() == null) { "已有今日或未完成计划，请继续原计划" }
        require(proposal.keys().asSequence().toSet() == setOf("title", "reason", "items"))
        val title = text(proposal, "title", 80); val reason = text(proposal, "reason", 800)
        val raw = proposal.getJSONArray("items"); require(raw.length() in 1..10)
        val values = (0 until raw.length()).map { raw.getJSONObject(it) }
        require(values.map { Question.normalize(it.getString("english")) }.distinct().size == values.size)
        var newWords = 0
        for(item in values) {
            require(item.keys().asSequence().toSet() == setOf("word_id", "english", "meaning", "example", "translation", "purpose"))
            val english = text(item, "english", 100); require(english.matches(Regex("[A-Za-z][A-Za-z '-]{0,99}")))
            text(item, "meaning", 500); text(item, "example", 500); text(item, "translation", 500)
            require(item.getString("purpose") in listOf("LEARN", "REVIEW"))
            val id = Arguments.integer(item.opt("word_id"),0,Long.MAX_VALUE)
            if(id > 0) require(Question.normalize(checkNotNull(store.word(id)).english) == Question.normalize(english))
            if(item.getString("purpose") == "REVIEW") require(id > 0)
            if(id == 0L) newWords++
        }
        require(newWords <= StudyPreferences(app).newWords.coerceAtMost(8)) { "新增词超过学习偏好" }
        val id = db.insertOrThrow("study_plans", null, ContentValues().apply { put("day", today()); put("title", title); put("reason", reason); put("updated", System.currentTimeMillis()) })
        for(item in values) {
            val wordId = if(item.getLong("word_id") > 0) item.getLong("word_id") else store.addWord(item.getString("english"))
            val word = checkNotNull(store.word(wordId))
            if(word.meaning.isBlank()) store.setMeaning(wordId, item.getString("meaning"))
            db.insertOrThrow("study_items", null, ContentValues().apply {
                put("plan_id", id); put("word_id", wordId); put("english", word.english)
                put("meaning", checkNotNull(store.word(wordId)).meaning); put("example", item.getString("example")); put("translation", item.getString("translation")); put("purpose", item.getString("purpose"))
            })
        }
        val selected = items(id).filter { it.purpose == "LEARN" }.ifEmpty { items(id) }.map { it.wordId }.toSet()
        val practice = build(id, Kind.ENGLISH_CHOICE, selected) + build(id, Kind.CLOZE, selected)
        writeQuestions(id, "PRACTICE", practice.ifEmpty { build(id, Kind.SPELLING, selected) }.take(5))
        writeExam(id, selected, Kind.ENGLISH_CHOICE, 3)
        if(items(id).none { it.purpose == "LEARN" }) change(id, "PRACTICE", 0)
        checkNotNull(get(id))
    }

    private fun build(id: Long, kind: Kind, selected: Set<Long>): List<Question> = Builder.build(store.words(), kind, selected, examples = items(id).associate { it.wordId to it.example })
    private fun writeQuestions(id: Long, phase: String, questions: List<Question>, listening: Boolean = false, start: Int = 0) {
        require(questions.isNotEmpty()) { "词卡资料不足，无法生成有效题目" }
        questions.forEachIndexed { index, q -> db.insertOrThrow("study_questions", null, ContentValues().apply {
            put("plan_id", id); put("phase", phase); put("position", start + index); put("snapshot", q.json().toString()); put("listening", if(listening) 1 else 0)
        }) }
    }

    private fun writeExam(id: Long, selected: Set<Long>, kind: Kind, count: Int) {
        val sequence = listOf(kind, Kind.CHOICE, Kind.SPELLING, kind, Kind.CHOICE)
        for(index in 0 until count) {
            val question = build(id, sequence[index], selected).firstOrNull() ?: build(id, Kind.SPELLING, selected).firstOrNull()
            requireNotNull(question) { "缺少无歧义测验资料，请补充词义" }
            writeQuestions(id, "EXAM", listOf(question), index == 1 && question.kind == Kind.CHOICE, index)
        }
    }

    fun markLearned(id: Long, itemId: Long) = transaction {
        val p = checkNotNull(get(id)); require(p.phase == "LEARN")
        val item = items(id).filter { it.purpose == "LEARN" }.getOrNull(p.cursor); require(item?.id == itemId)
        require(store.word(item.wordId) != null)
        db.execSQL("UPDATE study_items SET learned=1 WHERE id=?", arrayOf<Any>(itemId))
        ReviewSchedule.exposure(db, item.wordId)
        val next = p.cursor + 1
        change(id, if(next >= items(id).count { it.purpose == "LEARN" }) "PRACTICE" else "LEARN", if(next >= items(id).count { it.purpose == "LEARN" }) 0 else next)
    }

    fun draft(id: Long, value: String, expectedKey: String? = null) = transaction {
        val p = checkNotNull(get(id)); require(p.phase in listOf("PRACTICE", "EXAM") && value.length <= 500)
        val q = questions(id, p.phase)[p.cursor]; require(q.answer.isEmpty() && (expectedKey == null || expectedKey == q.question.key))
        db.execSQL("UPDATE study_questions SET draft=? WHERE plan_id=? AND phase=? AND position=?", arrayOf<Any>(value, id, p.phase, p.cursor))
    }

    fun assist(id: Long) = transaction {
        val p = checkNotNull(get(id)); require(p.phase in listOf("PRACTICE", "EXAM"))
        val q = questions(id, p.phase)[p.cursor]; require(q.answer.isEmpty() && (p.phase == "PRACTICE" || q.listening))
        db.execSQL("UPDATE study_questions SET assisted=1 WHERE plan_id=? AND phase=? AND position=?", arrayOf<Any>(id, p.phase, p.cursor)); bump(id)
    }

    fun replay(id: Long) = transaction {
        val p = checkNotNull(get(id)); require(p.phase == "EXAM" && questions(id, p.phase)[p.cursor].listening)
        db.execSQL("UPDATE study_questions SET replays=replays+1 WHERE plan_id=? AND phase=? AND position=?", arrayOf<Any>(id, p.phase, p.cursor))
    }

    fun submit(id: Long): StudyQuestion = transaction {
        val p = checkNotNull(get(id)); require(p.phase in listOf("PRACTICE", "EXAM"))
        val q = questions(id, p.phase)[p.cursor]
        if(q.answer.isEmpty()) {
            require(q.draft.isNotBlank() && store.word(q.question.wordId) != null)
            if(q.question.kind != Kind.SPELLING) require(q.draft in q.question.options)
            if(p.phase == "EXAM") {
                PracticeStore(app).submit(q.question, q.draft)
                ReviewSchedule.record(db, q.question.wordId, if(q.listening) "LISTEN" else q.question.kind.name, q.question.correct(q.draft) && !q.assisted)
            }
            if(p.phase=="PRACTICE")org.qingjian.speech.learning.archive.Store(app).event(q.question.wordId,q.question.english,"每日练习",JSONObject().put("answer",q.draft).put("correct",q.question.correct(q.draft)).put("assisted",q.assisted),"daily:$id:${q.question.key}")
            db.execSQL("UPDATE study_questions SET answer=draft WHERE plan_id=? AND phase=? AND position=?", arrayOf<Any>(id, p.phase, p.cursor)); bump(id)
        }
        questions(id, p.phase)[p.cursor]
    }

    fun advance(id: Long) = transaction {
        val p = checkNotNull(get(id))
        when(p.phase) {
            "PRACTICE", "EXAM" -> {
                val questions = questions(id, p.phase); require(questions[p.cursor].answer.isNotEmpty())
                val next = p.cursor + 1
                change(id, if(next < questions.size) p.phase else if(p.phase == "PRACTICE") "ADAPT" else "RESULT", if(next < questions.size) next else 0)
            }
            "EXAM_INTRO" -> change(id, "EXAM", 0)
            "RESULT" -> change(id, if(items(id).any { it.purpose == "REVIEW" }) "REVIEW" else "SUMMARY", 0)
            "SUMMARY" -> change(id, "DONE", 0)
            else -> error("请完成当前步骤")
        }
    }

    fun rate(id: Long, remembered: Boolean) = transaction {
        val p = checkNotNull(get(id)); require(p.phase == "REVIEW")
        val rows = items(id).filter { it.purpose == "REVIEW" }; val item = rows[p.cursor]; require(item.rating.isEmpty() && store.word(item.wordId) != null)
        db.execSQL("UPDATE study_items SET rating=? WHERE id=?", arrayOf<Any>(if(remembered) "remembered" else "again", item.id))
        store.reviewed(item.wordId); ReviewSchedule.record(db, item.wordId, "SELF", remembered)
        change(id, if(p.cursor + 1 < rows.size) "REVIEW" else "SUMMARY", if(p.cursor + 1 < rows.size) p.cursor + 1 else 0)
    }

    fun adjustment(id: Long, revision: Int, proposal: JSONObject, userRequest: String = ""): Plan = transaction {
        val p = requireRevision(id, revision); require(p.phase in listOf("LEARN", "PRACTICE", "ADAPT", "EXAM_INTRO"))
        require(proposal.keys().asSequence().toSet()==setOf("reason","kind","count","focus_word_ids"))
        val reason = text(proposal, "reason", 800); val kind = Kind.valueOf(proposal.getString("kind")); val count = Arguments.integer(proposal.opt("count"),1,5).toInt()
        require(userRequest.length<=1000)
        if(userRequest.isBlank()&&p.questionLimit>0)require(count<=p.questionLimit) { "后续题数超过你之前的调整要求，原计划保留" }
        val raw = proposal.getJSONArray("focus_word_ids"); require(raw.length() in 1..5)
        val ids = (0 until raw.length()).map { Arguments.integer(raw.opt(it),1,Long.MAX_VALUE) }.toSet(); require(ids.all { target -> items(id).any { it.wordId == target } })
        require(build(id,kind,ids).isNotEmpty()) { "所选题型资料不足，原小测保留；可改用已有词义或拼写题" }
        db.delete("study_questions", "plan_id=? AND phase='EXAM'", arrayOf(id.toString()))
        writeExam(id, ids, kind, count)
        val note=JSONObject().put("reason",reason).put("user_instruction",userRequest.ifBlank {p.userInstruction})
            .put("question_limit",if(userRequest.isNotBlank())count else p.questionLimit)
        db.execSQL("UPDATE study_plans SET adjustment=? WHERE id=?", arrayOf<Any>(note.toString(), id))
        change(id, if(p.phase == "ADAPT") "EXAM_INTRO" else p.phase, p.cursor)
        checkNotNull(get(id))
    }

    fun skipAdjustment(id: Long) = transaction { require(get(id)?.phase == "ADAPT"); change(id, "EXAM_INTRO", 0) }
    fun reflection(id: Long, revision: Int, proposal: JSONObject): Plan = transaction {
        val p = requireRevision(id, revision); require(p.phase in listOf("SUMMARY", "DONE"))
        require(proposal.keys().asSequence().toSet()==setOf("suggestion"))
        val value = text(proposal, "suggestion", 1800)
        db.execSQL("UPDATE study_plans SET reflection=? WHERE id=?", arrayOf<Any>(value, id)); bump(id); checkNotNull(get(id))
    }

    fun results(id: Long): JSONObject {
        val exams = questions(id, "EXAM"); val submitted = exams.filter { it.answer.isNotEmpty() }
        return JSONObject().put("planned", exams.size).put("submitted", submitted.size).put("correct", submitted.count { it.correct })
            .put("independent_correct", submitted.count { it.correct && !it.assisted }).put("assisted", submitted.count { it.assisted })
            .put("exam_results", JSONArray(submitted.map { JSONObject().put("word_id", it.question.wordId).put("english", it.question.english).put("kind", if(it.listening) "LISTEN" else it.question.kind.name).put("correct", it.correct).put("assisted", it.assisted).put("replays", it.replays) }))
            .put("practice_results", JSONArray(questions(id, "PRACTICE").filter { it.answer.isNotEmpty() }.map { JSONObject().put("word_id", it.question.wordId).put("correct", it.correct).put("assisted", it.assisted) }))
            .put("review_results", JSONArray(items(id).filter { it.rating.isNotEmpty() }.map { JSONObject().put("word_id", it.wordId).put("self_rating", it.rating) }))
    }

    private fun requireRevision(id: Long, revision: Int) = checkNotNull(get(id)).also { require(it.revision == revision) { "学习进度已变化，本次建议未覆盖已有资料" } }
    private fun bump(id: Long) { db.execSQL("UPDATE study_plans SET revision=revision+1,updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), id)) }
    private fun change(id: Long, phase: String, cursor: Int) { db.execSQL("UPDATE study_plans SET phase=?,cursor=? WHERE id=?", arrayOf<Any>(phase, cursor, id)); bump(id) }
    private fun <T> transaction(block: () -> T): T { db.beginTransaction(); try { val result = block(); db.setTransactionSuccessful(); return result } finally { db.endTransaction() } }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        fun text(value: JSONObject, key: String, max: Int): String = Arguments.text(value,key,max)
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE study_meta(id INTEGER PRIMARY KEY CHECK(id=1),epoch INTEGER NOT NULL)")
            db.execSQL("INSERT INTO study_meta VALUES(1,0)")
            db.execSQL("CREATE TABLE study_plans(id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL UNIQUE,title TEXT NOT NULL,reason TEXT NOT NULL,phase TEXT NOT NULL DEFAULT 'LEARN',cursor INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,adjustment TEXT NOT NULL DEFAULT '',reflection TEXT NOT NULL DEFAULT '',updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE study_items(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER NOT NULL,word_id INTEGER NOT NULL,english TEXT NOT NULL,meaning TEXT NOT NULL,example TEXT NOT NULL,translation TEXT NOT NULL,purpose TEXT NOT NULL,learned INTEGER NOT NULL DEFAULT 0,rating TEXT NOT NULL DEFAULT '',UNIQUE(plan_id,word_id))")
            db.execSQL("CREATE TABLE study_questions(plan_id INTEGER NOT NULL,phase TEXT NOT NULL,position INTEGER NOT NULL,snapshot TEXT NOT NULL,listening INTEGER NOT NULL DEFAULT 0,draft TEXT NOT NULL DEFAULT '',assisted INTEGER NOT NULL DEFAULT 0,answer TEXT NOT NULL DEFAULT '',replays INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(plan_id,phase,position))")
            db.execSQL("CREATE TABLE study_reviews(word_id INTEGER NOT NULL,skill TEXT NOT NULL,streak INTEGER NOT NULL,next_due INTEGER NOT NULL,last_at INTEGER NOT NULL,PRIMARY KEY(word_id,skill))")
        }
    }
}
