//! 学习操作快照与幂等应用；记录新增来源、保护撤销条件，并保留旧版草稿。
package org.qingjian.speech.learning.ai.tools

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.quiz.Builder
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.Question
import java.security.MessageDigest

class ActionStore(context: Context) {
    private val app = context.applicationContext
    private val store = LearningStore.get(context)

    fun queue(topic: String, question: Long, kind: String, payload: JSONObject): Action {
        require(kind in setOf("prepare_word_card", "prepare_practice", "request_pronunciation"))
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            require(db.rawQuery("SELECT 1 FROM messages WHERE id=? AND topic=? AND role='user'", arrayOf(question.toString(), topic)).use { it.moveToFirst() }) { "原问题已清除，操作草稿未保存" }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val existing = db.rawQuery("SELECT id FROM learning_actions WHERE question_id=? AND kind=? AND fingerprint=?",
                arrayOf(question.toString(), kind, fingerprint)).use { if(it.moveToFirst()) it.getLong(0) else null }
            if(existing == null) require(db.rawQuery("SELECT COUNT(*) FROM learning_actions WHERE topic=? AND state='pending'", arrayOf(topic)).use { it.moveToFirst(); it.getInt(0) } < 20) { "请先处理已有的待确认操作" }
            val id = existing ?: db.insertOrThrow("learning_actions", null, ContentValues().apply {
                put("topic", topic); put("question_id", question); put("kind", kind); put("payload", payload.toString())
                put("fingerprint", fingerprint); put("state", "pending"); put("word_id", 0); put("created", System.currentTimeMillis())
            })
            val action = checkNotNull(get(id)); db.setTransactionSuccessful(); return action
        } finally { db.endTransaction() }
    }

    private val columns = "id,kind,payload,state,word_id,automatic,created_word,question_id"
    private fun from(cursor: Cursor) = Action(cursor.getLong(0), cursor.getString(1), JSONObject(cursor.getString(2)), cursor.getString(3), cursor.getLong(4), cursor.getInt(5) != 0, cursor.getInt(6) != 0, cursor.getLong(7))

    fun get(id: Long): Action? = store.readableDatabase.rawQuery("SELECT $columns FROM learning_actions WHERE id=?", arrayOf(id.toString())).use {
        if(it.moveToFirst()) from(it) else null
    }

    fun forTopic(topic: String): List<Action> = store.readableDatabase.rawQuery(
        "SELECT $columns FROM learning_actions WHERE topic=? ORDER BY (state='pending') DESC,id DESC LIMIT 20", arrayOf(topic)).use { cursor ->
        buildList { while(cursor.moveToNext()) add(from(cursor)) }.asReversed()
    }

    fun approvedCard(wordId: Long): JSONObject? = org.qingjian.speech.learning.notes.Store(app).card(wordId)

    fun dismiss(id: Long) { store.writableDatabase.update("learning_actions", ContentValues().apply { put("state", "dismissed") }, "id=? AND state='pending'", arrayOf(id.toString())) }

    fun saveCard(id: Long): Long {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val action = checkNotNull(get(id)) { "操作已被清除" }
            require(action.kind == "prepare_word_card" && action.state in listOf("pending", "applied")) { "词卡草稿已取消或不适用" }
            if(action.state == "applied") { db.setTransactionSuccessful(); return action.wordId }
            val data = Arguments.card(action.payload)
            val existed = db.rawQuery("SELECT 1 FROM words WHERE lookup=?", arrayOf(data.getString("english").lowercase(java.util.Locale.ROOT))).use { it.moveToFirst() }
            val wordId = store.addWord(data.getString("english"))
            if(store.word(wordId)?.meaning.isNullOrBlank()) store.setMeaning(wordId, data.getString("meaning"))
            db.update("learning_actions", ContentValues().apply {
                put("state", "applied"); put("word_id", wordId); put("created_word", if(existed) 0 else 1)
                put("word_snapshot", checkNotNull(store.word(wordId)).toString())
            }, "id=? AND state='pending'", arrayOf(id.toString()))
            db.setTransactionSuccessful(); return wordId
        } finally { db.endTransaction() }
    }

    fun practice(id: Long): List<Question> {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val action = checkNotNull(get(id)) { "操作已被清除" }
            require(action.kind == "prepare_practice" && action.state in listOf("pending", "applied")) { "练习草稿已取消或不适用" }
            val data = action.payload
            val questions = if(action.state == "applied") {
                val values = data.getJSONArray("questions")
                (0 until values.length()).map { Question.from(values.getJSONObject(it)) }
            } else {
                val ids = Arguments.ids(data)
                val chosen = ids.map { checkNotNull(store.word(it)) { "词条已删除，请重新准备" } }
                val pool = (store.words() + chosen).distinctBy { it.id }
                val examples = ids.mapNotNull { id -> approvedCard(id)?.optString("example")?.let { id to it } }.toMap()
                Builder.build(pool, Kind.valueOf(data.getString("kind")), ids.toSet(), examples = examples).also {
                    require(it.size == ids.size) { "部分词条缺少笔记或答案有歧义，请补充后重试" }
                }
            }
            require(questions.all { store.word(it.wordId)?.english == it.english }) { "练习词条已删除，请重新准备" }
            data.put("questions", JSONArray(questions.map { it.json() }))
            db.update("learning_actions", ContentValues().apply { put("state", "applied"); put("payload", data.toString()) }, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful(); return questions
        } finally { db.endTransaction() }
    }

    fun claimPronunciation(id: Long): String {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val action = checkNotNull(get(id)) { "操作已被清除" }
            require(action.kind == "request_pronunciation" && action.state == "pending") { "朗读请求已处理，请重新发起" }
            val word = checkNotNull(store.word(action.payload.getLong("word_id"))) { "词条已删除" }
            db.update("learning_actions", ContentValues().apply { put("state", "applied"); put("word_id", word.id) }, "id=? AND state='pending'", arrayOf(id.toString()))
            db.setTransactionSuccessful(); return word.english
        } finally { db.endTransaction() }
    }

    fun releasePronunciation(id: Long) {
        store.writableDatabase.update("learning_actions", ContentValues().apply { put("state", "pending") },
            "id=? AND kind=? AND state=?", arrayOf(id.toString(), "request_pronunciation", "applied"))
    }

    fun markAutomatic(id: Long) { store.writableDatabase.execSQL("UPDATE learning_actions SET automatic=1 WHERE id=?", arrayOf(id)) }

    fun questionText(topic: String, id: Long): String? = store.readableDatabase.rawQuery(
        "SELECT body FROM messages WHERE topic=? AND id=? AND role='user'", arrayOf(topic, id.toString())).use { if(it.moveToFirst()) it.getString(0) else null }

    fun automaticCards(question: Long): Int = store.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM learning_actions WHERE question_id=? AND kind='prepare_word_card' AND automatic=1 AND created_word=1 AND state='applied'", arrayOf(question.toString())).use { it.moveToFirst(); it.getInt(0) }

    fun retry(id: Long) { store.writableDatabase.execSQL("UPDATE learning_actions SET state='pending' WHERE id=? AND state='failed'", arrayOf(id)) }

    fun failed(id: Long) { store.writableDatabase.execSQL("UPDATE learning_actions SET state='failed' WHERE id=? AND state='pending'", arrayOf(id)) }

    fun advancePractice(id: Long) {
        val action = checkNotNull(get(id)); val data = action.payload
        val total = data.getJSONArray("questions").length()
        data.put("index", (data.optInt("index") + 1).coerceAtMost(total))
        store.writableDatabase.update("learning_actions", ContentValues().apply { put("payload", data.toString()) }, "id=? AND state='applied'", arrayOf(id.toString()))
    }

    fun undoCard(id: Long): Boolean {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            val action = get(id) ?: return false
            if(action.kind != "prepare_word_card" || action.state != "applied" || !action.createdWord) return false
            val snapshot = db.rawQuery("SELECT word_snapshot FROM learning_actions WHERE id=?", arrayOf(id.toString())).use { it.moveToFirst(); it.getString(0) }
            val word = store.word(action.wordId) ?: return false
            if(word.toString() != snapshot) return false
            for(table in listOf("quiz_attempts", "oral_recordings", "conversations", "study_items", "word_materials")) {
                if(db.rawQuery("SELECT 1 FROM $table WHERE word_id=? LIMIT 1", arrayOf(word.id.toString())).use { it.moveToFirst() }) return false
            }
            if(db.rawQuery("SELECT 1 FROM learning_actions WHERE id!=? AND state='applied' AND word_id=? LIMIT 1", arrayOf(id.toString(), word.id.toString())).use { it.moveToFirst() }) return false
            val referenced = db.rawQuery("SELECT payload FROM learning_actions WHERE kind='prepare_practice' AND state='applied'", null).use { c ->
                var used = false
                while(c.moveToNext()) if(runCatching { Arguments.ids(JSONObject(c.getString(0))).contains(word.id) }.getOrDefault(false)) used = true
                used
            }
            if(referenced) return false
            db.delete("words", "id=?", arrayOf(word.id.toString()))
            db.update("learning_actions", ContentValues().apply { put("state", "undone") }, "id=?", arrayOf(id.toString()))
            if(org.qingjian.speech.learning.StudyPreferences(app).lastWord == word.id) org.qingjian.speech.learning.StudyPreferences(app).lastWord = 0
            db.setTransactionSuccessful(); return true
        } finally { db.endTransaction() }
    }

    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE learning_actions (id INTEGER PRIMARY KEY AUTOINCREMENT,topic TEXT NOT NULL,question_id INTEGER NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,fingerprint TEXT NOT NULL,state TEXT NOT NULL,word_id INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,automatic INTEGER NOT NULL DEFAULT 0,created_word INTEGER NOT NULL DEFAULT 0,word_snapshot TEXT NOT NULL DEFAULT '',UNIQUE(question_id,kind,fingerprint))")
            db.execSQL("CREATE INDEX actions_topic ON learning_actions(topic,id)")
            db.execSQL("CREATE INDEX actions_word ON learning_actions(word_id,kind,state)")
        }
    }
}
