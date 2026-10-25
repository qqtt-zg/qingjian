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

data class StageRecord(
    val stageKey: String,
    val planId: Long,
    val itemId: Long,
    val wordId: Long,
    val position: Int,
    val dimension: String,
    val stageType: String,
    val snapshot: String,
    val credit: Int,
    val status: String,
    val draft: String,
    val assisted: Boolean,
    val resultEventKey: String?,
    val activeMs: Long,
    val measurementValid: Boolean,
    val updated: Long
)

class PlanStore(private val app: Context) {
    private val store = LearningStore.get(app)
    private val db get() = store.writableDatabase
    private val columns = "id,day,title,reason,phase,cursor,revision,adjustment,reflection,round_no,format_version,status,planned_credit,done_credit,active_ms,measurement_valid,preferences_revision,rule_version,plan_revision_no,budget_snapshot"

    fun current(): Plan? = query(
        "((format_version=1 AND phase!='DONE') OR (format_version>=2 AND status IN ('READY','RUNNING','PAUSED'))) ORDER BY id DESC LIMIT 1"
    ) ?: query("day=? ORDER BY round_no DESC, id DESC LIMIT 1", arrayOf(today()))
    fun get(id: Long): Plan? = query("id=?", arrayOf(id.toString()))
    private fun query(where: String, args: Array<String>? = null): Plan? = db.rawQuery("SELECT $columns FROM study_plans WHERE $where", args).use { c ->
        if(!c.moveToFirst()) null else Plan(
            id = c.getLong(0),
            day = c.getString(1),
            title = c.getString(2),
            reason = c.getString(3),
            phase = c.getString(4),
            cursor = c.getInt(5),
            revision = c.getInt(6),
            adjustment = c.getString(7),
            reflection = c.getString(8),
            roundNo = c.getInt(9),
            formatVersion = c.getInt(10),
            status = c.getString(11),
            plannedCredit = c.getInt(12),
            doneCredit = c.getInt(13),
            activeMs = c.getLong(14),
            measurementValid = c.getInt(15) == 1,
            preferencesRevision = c.getInt(16),
            ruleVersion = c.getInt(17),
            planRevisionNo = c.getInt(18),
            budgetSnapshot = c.getString(19)
        )
    }

    fun items(id: Long): List<PlanItem> = db.rawQuery("SELECT id,word_id,english,meaning,example,translation,purpose,learned,rating,reason_code,reason_text,item_key FROM study_items WHERE plan_id=? ORDER BY id", arrayOf(id.toString())).use { c ->
        buildList { while(c.moveToNext()) add(PlanItem(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getInt(7) == 1, c.getString(8), c.getString(9), c.getString(10), c.getString(11))) }
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

    fun recentHistory(): List<PastDayPlanRecord> = db.rawQuery(
        "SELECT day, round_no, format_version, status, planned_credit, done_credit, active_ms, measurement_valid, preferences_revision FROM study_plans ORDER BY id DESC LIMIT 30",
        null
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    PastDayPlanRecord(
                        day = c.getString(0),
                        roundNo = c.getInt(1),
                        formatVersion = c.getInt(2),
                        status = c.getString(3),
                        plannedCredit = c.getInt(4),
                        doneCredit = c.getInt(5),
                        activeMs = c.getLong(6),
                        measurementValid = c.getInt(7) == 1,
                        preferencesRevision = c.getInt(8)
                    )
                )
            }
        }
    }

    /** 读取某计划的所有环节列表 */
    fun stages(planId: Long): List<StageRecord> = db.rawQuery(
        "SELECT stage_key, plan_id, item_id, word_id, position, dimension, stage_type, snapshot, credit, status, draft, assisted, result_event_key, active_ms, measurement_valid, updated FROM study_item_stages WHERE plan_id=? ORDER BY position",
        arrayOf(planId.toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    StageRecord(
                        stageKey = c.getString(0),
                        planId = c.getLong(1),
                        itemId = c.getLong(2),
                        wordId = c.getLong(3),
                        position = c.getInt(4),
                        dimension = c.getString(5),
                        stageType = c.getString(6),
                        snapshot = c.getString(7),
                        credit = c.getInt(8),
                        status = c.getString(9),
                        draft = c.getString(10),
                        assisted = c.getInt(11) == 1,
                        resultEventKey = if (c.isNull(12)) null else c.getString(12),
                        activeMs = c.getLong(13),
                        measurementValid = c.getInt(14) == 1,
                        updated = c.getLong(15)
                    )
                )
            }
        }
    }

    /** 写入环节草稿 */
    fun saveStageDraft(stageKey: String, draft: String) = transaction {
        require(draft.length <= 500)
        db.execSQL(
            "UPDATE study_item_stages SET draft=?, updated=? WHERE stage_key=? AND status='pending'",
            arrayOf<Any>(draft, System.currentTimeMillis(), stageKey)
        )
    }

    /** 环节推进完成 */
    fun completeStage(stageKey: String, eventKey: String?, assisted: Boolean, activeMs: Long) = transaction {
        val stage = db.rawQuery(
            "SELECT plan_id, credit, status, word_id FROM study_item_stages WHERE stage_key=?",
            arrayOf(stageKey)
        ).use { c ->
            if (c.moveToFirst()) Tuple4(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3)) else null
        } ?: error("环节不存在")
        require(stage.status == "pending") { "环节已非 pending 状态" }

        val planId = stage.planId
        val credit = stage.credit
        val wordId = stage.wordId

        db.execSQL(
            "UPDATE study_item_stages SET status='done', result_event_key=?, assisted=?, active_ms=active_ms+?, updated=? WHERE stage_key=?",
            arrayOf<Any>(eventKey ?: "", if (assisted) 1 else 0, activeMs, System.currentTimeMillis(), stageKey)
        )
        db.execSQL(
            "UPDATE study_plans SET done_credit=done_credit+?, active_ms=active_ms+?, status='RUNNING', updated=? WHERE id=?",
            arrayOf<Any>(credit, activeMs, System.currentTimeMillis(), planId)
        )

        // 标记该词关联的待练意图已消费
        org.qingjian.speech.learning.intent.StudyIntentStore(app).markConsumed(wordId)

        val remaining = db.rawQuery(
            "SELECT COUNT(1) FROM study_item_stages WHERE plan_id=? AND status='pending'",
            arrayOf(planId.toString())
        ).use { it.moveToFirst(); it.getInt(0) }
        if (remaining == 0) {
            db.execSQL("UPDATE study_plans SET status='DONE', updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), planId))
        }
        bump(planId)
    }

    private data class Tuple4<A, B, C, D>(val planId: A, val credit: B, val status: C, val wordId: D)

    /** 跳过环节 */
    fun skipStage(stageKey: String) = transaction {
        val planId = db.rawQuery("SELECT plan_id FROM study_item_stages WHERE stage_key=?", arrayOf(stageKey)).use {
            if (it.moveToFirst()) it.getLong(0) else error("环节不存在")
        }
        db.execSQL(
            "UPDATE study_item_stages SET status='skipped', updated=? WHERE stage_key=? AND status='pending'",
            arrayOf<Any>(System.currentTimeMillis(), stageKey)
        )
        val remaining = db.rawQuery(
            "SELECT COUNT(1) FROM study_item_stages WHERE plan_id=? AND status='pending'",
            arrayOf(planId.toString())
        ).use { it.moveToFirst(); it.getInt(0) }
        if (remaining == 0) {
            db.execSQL("UPDATE study_plans SET status='DONE', updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis(), planId))
        }
        bump(planId)
    }

    fun pausePlan(planId: Long, activeMs: Long) = transaction {
        db.execSQL(
            "UPDATE study_plans SET status='PAUSED', active_ms=active_ms+?, updated=? WHERE id=? AND status='RUNNING'",
            arrayOf<Any>(activeMs, System.currentTimeMillis(), planId)
        )
        bump(planId)
    }

    fun resumePlan(planId: Long) = transaction {
        db.execSQL(
            "UPDATE study_plans SET status='RUNNING', updated=? WHERE id=? AND status IN ('READY', 'PAUSED')",
            arrayOf<Any>(System.currentTimeMillis(), planId)
        )
        bump(planId)
    }

    fun abandonPlan(planId: Long) = transaction {
        db.execSQL(
            "UPDATE study_plans SET status='ABANDONED', updated=? WHERE id=? AND status IN ('READY', 'RUNNING', 'PAUSED')",
            arrayOf<Any>(System.currentTimeMillis(), planId)
        )
        bump(planId)
    }

    /** 保存计划修订记录并递增 plan_revision_no */
    fun saveRevision(record: PlanRevisionRecord) = transaction {
        val values = ContentValues().apply {
            put("plan_id", record.planId)
            put("revision_no", record.revisionNo)
            put("action", record.action)
            put("before_snapshot", record.beforeSnapshot)
            put("after_snapshot", record.afterSnapshot)
            put("user_constraints", record.userConstraints)
            put("reason", record.reason)
            put("created", record.timestamp)
        }
        db.insertWithOnConflict("plan_revisions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "UPDATE study_plans SET plan_revision_no=plan_revision_no+1, updated=? WHERE id=?",
            arrayOf<Any>(System.currentTimeMillis(), record.planId)
        )
        bump(record.planId)
    }

    /** 查询最近 N 天内各词汇的跳过历史 */
    fun skippedWordsHistory(days: Int = 7): Map<Long, SkipHistory> {
        val cutoff = System.currentTimeMillis() - days * 86400000L
        val historyMap = mutableMapOf<Long, MutableList<Pair<String, String>>>()

        db.rawQuery(
            "SELECT p.day, s.word_id, s.dimension FROM study_item_stages s JOIN study_plans p ON s.plan_id = p.id WHERE s.status = 'skipped' AND s.updated >= ? ORDER BY p.day DESC",
            arrayOf(cutoff.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getString(0)
                val wordId = c.getLong(1)
                val dim = c.getString(2)
                historyMap.getOrPut(wordId) { mutableListOf() }.add(day to dim)
            }
        }

        return historyMap.mapValues { (wordId, list) ->
            val distinctDays = list.map { it.first }.distinct()
            val lastDim = list.firstOrNull()?.second
            SkipHistory(
                wordId = wordId,
                consecutiveSkipDays = distinctDays.size,
                lastSkippedDimension = lastDim
            )
        }
    }

    /** 将已有 v2 计划还原为 ProposedPlan 以便进入修订流程 */
    fun toProposedPlan(planId: Long): ProposedPlan {
        val p = checkNotNull(get(planId))
        val itemsList = items(planId)
        val stagesList = stages(planId)
        val stagesByItem = stagesList.groupBy { it.itemId }

        val proposedItems = itemsList.map { item ->
            val itemStages = stagesByItem[item.id].orEmpty().sortedBy { it.position }.map { s ->
                ProposedStage(
                    stageType = s.stageType,
                    dimension = s.dimension,
                    credit = s.credit,
                    questionSnapshot = s.snapshot
                )
            }
            ProposedItem(
                wordId = item.wordId,
                english = item.english,
                meaning = item.meaning,
                example = item.example,
                translation = item.translation,
                reasonCode = item.reasonCode,
                reasonText = item.reasonText,
                stages = itemStages
            )
        }
        return ProposedPlan(
            title = p.title,
            reason = p.reason,
            items = proposedItems,
            plannedCredit = p.plannedCredit,
            budgetSnapshotJson = p.budgetSnapshot
        )
    }

    /** 应用修订后的计划并更新数据库中的 study_items 与 pending 的 study_item_stages */
    fun applyRevision(
        planId: Long,
        revisedPlan: ProposedPlan,
        auditRecord: PlanRevisionRecord? = null
    ): Plan = transaction {
        val cur = checkNotNull(get(planId))
        val existingStages = stages(planId)
        val existingItems = items(planId)

        val immutableStages = existingStages.filter { it.status in listOf("done", "skipped") }
        val immutableStageKeys = immutableStages.map { it.stageKey }.toSet()
        val immutableWordIds = immutableStages.map { it.wordId }.toSet()

        // 删除仍处于 pending 状态的旧 stages
        db.execSQL("DELETE FROM study_item_stages WHERE plan_id=? AND status='pending'", arrayOf(planId.toString()))

        // 删除不再使用的未学习 items
        val revisedWordIds = revisedPlan.items.map { it.wordId }.toSet()
        for (item in existingItems) {
            if (item.wordId !in revisedWordIds && item.wordId !in immutableWordIds) {
                db.delete("study_items", "id=?", arrayOf(item.id.toString()))
            }
        }

        var globalPos = 0
        // 先排已完成/跳过的 stages
        for (s in immutableStages.sortedBy { it.position }) {
            db.execSQL("UPDATE study_item_stages SET position=? WHERE stage_key=?", arrayOf<Any>(globalPos++, s.stageKey))
        }

        val updatedItems = items(planId).associateBy { it.wordId }

        for (item in revisedPlan.items) {
            val existingItem = updatedItems[item.wordId]
            val itemId = if (existingItem != null) {
                existingItem.id
            } else {
                val itemKey = "$planId:${item.wordId}"
                val itemValues = ContentValues().apply {
                    put("plan_id", planId)
                    put("word_id", item.wordId)
                    put("english", item.english)
                    put("meaning", item.meaning)
                    put("example", item.example)
                    put("translation", item.translation)
                    put("purpose", if (item.reasonCode.startsWith("due") || item.reasonCode.startsWith("consolidate")) "REVIEW" else "LEARN")
                    put("learned", 0)
                    put("rating", "")
                    put("reason_code", item.reasonCode)
                    put("reason_text", item.reasonText)
                    put("item_key", itemKey)
                }
                db.insertOrThrow("study_items", null, itemValues)
            }

            for (stage in item.stages) {
                val stageKey = "$planId:$itemId:${stage.dimension}:${stage.stageType}"
                if (stageKey in immutableStageKeys) continue

                val stageValues = ContentValues().apply {
                    put("stage_key", stageKey)
                    put("plan_id", planId)
                    put("item_id", itemId)
                    put("word_id", item.wordId)
                    put("position", globalPos++)
                    put("dimension", stage.dimension)
                    put("stage_type", stage.stageType)
                    put("snapshot", stage.questionSnapshot)
                    put("credit", stage.credit)
                    put("status", "pending")
                    put("draft", "")
                    put("assisted", 0)
                    put("active_ms", 0L)
                    put("measurement_valid", 1)
                    put("updated", System.currentTimeMillis())
                }
                db.insertWithOnConflict("study_item_stages", null, stageValues, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }

        val totalPlannedCredit = immutableStages.sumOf { it.credit } +
            stages(planId).filter { it.status == "pending" }.sumOf { it.credit }

        db.execSQL(
            "UPDATE study_plans SET planned_credit=?, title=?, reason=?, updated=? WHERE id=?",
            arrayOf<Any>(totalPlannedCredit, revisedPlan.title, revisedPlan.reason, System.currentTimeMillis(), planId)
        )

        if (auditRecord != null) {
            saveRevision(auditRecord)
        } else {
            db.execSQL(
                "UPDATE study_plans SET plan_revision_no=plan_revision_no+1, updated=? WHERE id=?",
                arrayOf<Any>(System.currentTimeMillis(), planId)
            )
            bump(planId)
        }

        checkNotNull(get(planId))
    }

    /** 创建 format_version = 2 计划 */
    fun createV2(plan: ProposedPlan, expectedEpoch: Long = epoch()): Plan = transaction {
        require(epoch() == expectedEpoch) { "资料已清空，本次计划未保存" }
        val cur = current()
        require(cur == null || cur.status in listOf("DONE", "ABANDONED") || (cur.formatVersion == 1 && cur.phase == "DONE")) {
            "已有进行中的计划，请继续原计划"
        }

        val todayStr = today()
        val latestRound = db.rawQuery(
            "SELECT MAX(round_no) FROM study_plans WHERE day=?",
            arrayOf(todayStr)
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else -1 }
        val nextRound = if (latestRound >= 0) latestRound + 1 else 0

        val planValues = ContentValues().apply {
            put("day", todayStr)
            put("round_no", nextRound)
            put("format_version", 2)
            put("status", "READY")
            put("title", plan.title)
            put("reason", plan.reason)
            put("phase", "STAGES")
            put("cursor", 0)
            put("revision", 0)
            put("planned_credit", plan.plannedCredit)
            put("done_credit", 0)
            put("active_ms", 0L)
            put("measurement_valid", 1)
            put("preferences_revision", StudyPreferences(app).revision)
            put("rule_version", 1)
            put("plan_revision_no", 0)
            put("budget_snapshot", plan.budgetSnapshotJson)
            put("updated", System.currentTimeMillis())
        }
        val planId = db.insertOrThrow("study_plans", null, planValues)

        var globalPos = 0
        for (item in plan.items) {
            val itemKey = "$planId:${item.wordId}"
            val itemValues = ContentValues().apply {
                put("plan_id", planId)
                put("word_id", item.wordId)
                put("english", item.english)
                put("meaning", item.meaning)
                put("example", item.example)
                put("translation", item.translation)
                put("purpose", if (item.reasonCode.startsWith("due") || item.reasonCode.startsWith("consolidate")) "REVIEW" else "LEARN")
                put("learned", 0)
                put("rating", "")
                put("reason_code", item.reasonCode)
                put("reason_text", item.reasonText)
                put("item_key", itemKey)
            }
            val itemId = db.insertOrThrow("study_items", null, itemValues)

            for (stage in item.stages) {
                val stageKey = "$planId:$itemId:${stage.dimension}:${stage.stageType}"
                val stageValues = ContentValues().apply {
                    put("stage_key", stageKey)
                    put("plan_id", planId)
                    put("item_id", itemId)
                    put("word_id", item.wordId)
                    put("position", globalPos++)
                    put("dimension", stage.dimension)
                    put("stage_type", stage.stageType)
                    put("snapshot", stage.questionSnapshot)
                    put("credit", stage.credit)
                    put("status", "pending")
                    put("draft", "")
                    put("assisted", 0)
                    put("active_ms", 0L)
                    put("measurement_valid", 1)
                    put("updated", System.currentTimeMillis())
                }
                db.insertOrThrow("study_item_stages", null, stageValues)
            }
        }

        val intentStore = org.qingjian.speech.learning.intent.StudyIntentStore(app)
        val chosenWordIds = plan.items.map { it.wordId }.toSet()
        val activeIntents = intentStore.getActiveIntents().filter { it.wordId in chosenWordIds }
        if (activeIntents.isNotEmpty()) {
            intentStore.markScheduled(activeIntents.map { it.id }, planId)
        }

        checkNotNull(get(planId))
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
            db.execSQL("CREATE TABLE study_plans(id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL,round_no INTEGER NOT NULL DEFAULT 0,format_version INTEGER NOT NULL DEFAULT 1,status TEXT NOT NULL DEFAULT 'READY',title TEXT NOT NULL,reason TEXT NOT NULL,phase TEXT NOT NULL DEFAULT 'LEARN',cursor INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,adjustment TEXT NOT NULL DEFAULT '',reflection TEXT NOT NULL DEFAULT '',planned_credit INTEGER NOT NULL DEFAULT 0,done_credit INTEGER NOT NULL DEFAULT 0,active_ms INTEGER NOT NULL DEFAULT 0,measurement_valid INTEGER NOT NULL DEFAULT 0,preferences_revision INTEGER NOT NULL DEFAULT 0,rule_version INTEGER NOT NULL DEFAULT 1,plan_revision_no INTEGER NOT NULL DEFAULT 0,budget_snapshot TEXT NOT NULL DEFAULT '{}',updated INTEGER NOT NULL,UNIQUE(day,round_no))")
            db.execSQL("CREATE INDEX study_plans_day_round ON study_plans(day, round_no)")
            db.execSQL("CREATE INDEX study_plans_status ON study_plans(status, format_version)")
            db.execSQL("CREATE TABLE study_items(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER NOT NULL,word_id INTEGER NOT NULL,english TEXT NOT NULL,meaning TEXT NOT NULL,example TEXT NOT NULL,translation TEXT NOT NULL,purpose TEXT NOT NULL,learned INTEGER NOT NULL DEFAULT 0,rating TEXT NOT NULL DEFAULT '',reason_code TEXT NOT NULL DEFAULT '',reason_text TEXT NOT NULL DEFAULT '',item_key TEXT NOT NULL DEFAULT '',UNIQUE(plan_id,word_id))")
            db.execSQL("CREATE TABLE study_item_stages(stage_key TEXT PRIMARY KEY,plan_id INTEGER NOT NULL,item_id INTEGER NOT NULL,word_id INTEGER NOT NULL,position INTEGER NOT NULL,dimension TEXT NOT NULL,stage_type TEXT NOT NULL,snapshot TEXT NOT NULL DEFAULT '',credit INTEGER NOT NULL DEFAULT 1,status TEXT NOT NULL DEFAULT 'pending',draft TEXT NOT NULL DEFAULT '',assisted INTEGER NOT NULL DEFAULT 0,result_event_key TEXT,active_ms INTEGER NOT NULL DEFAULT 0,measurement_valid INTEGER NOT NULL DEFAULT 1,updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX stages_plan_pos ON study_item_stages(plan_id, position)")
            db.execSQL("CREATE INDEX stages_word ON study_item_stages(word_id)")
            db.execSQL("CREATE TABLE plan_revisions(id INTEGER PRIMARY KEY AUTOINCREMENT,plan_id INTEGER NOT NULL,revision_no INTEGER NOT NULL,action TEXT NOT NULL,before_snapshot TEXT NOT NULL DEFAULT '',after_snapshot TEXT NOT NULL DEFAULT '',user_constraints TEXT NOT NULL DEFAULT '',reason TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,UNIQUE(plan_id,revision_no))")
            db.execSQL("CREATE INDEX plan_revisions_plan ON plan_revisions(plan_id, revision_no)")
            db.execSQL("CREATE TABLE study_questions(plan_id INTEGER NOT NULL,phase TEXT NOT NULL,position INTEGER NOT NULL,snapshot TEXT NOT NULL,listening INTEGER NOT NULL DEFAULT 0,draft TEXT NOT NULL DEFAULT '',assisted INTEGER NOT NULL DEFAULT 0,answer TEXT NOT NULL DEFAULT '',replays INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(plan_id,phase,position))")
            db.execSQL("CREATE TABLE study_reviews(word_id INTEGER NOT NULL,skill TEXT NOT NULL,streak INTEGER NOT NULL,next_due INTEGER NOT NULL,last_at INTEGER NOT NULL,PRIMARY KEY(word_id,skill))")
        }
    }
}
