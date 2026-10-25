//! 本地学习资料与会话仓库；第一阶段复用系统 SQLite，不引入额外运行依赖。
package org.qingjian.speech.learning

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.oral.AudioFiles

class LearningStore private constructor(private val app: Context) : SQLiteOpenHelper(app, "learning.db", null, 13) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE words (id INTEGER PRIMARY KEY AUTOINCREMENT, lookup TEXT NOT NULL UNIQUE, english TEXT NOT NULL, meaning TEXT NOT NULL DEFAULT '', gloss_origin TEXT NOT NULL DEFAULT 'unknown', received INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, reviewed INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL, note_revision INTEGER NOT NULL DEFAULT 0)")
        org.qingjian.speech.learning.notes.Store.createRevision(db)
        org.qingjian.speech.learning.notes.Store.createMaterials(db)
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, reply_to INTEGER)")
        db.execSQL("CREATE INDEX messages_topic ON messages(topic, id)")
        db.execSQL("CREATE UNIQUE INDEX messages_reply ON messages(reply_to) WHERE reply_to IS NOT NULL")
        PracticeStore.createTables(db)
        org.qingjian.speech.learning.ai.tools.ActionStore.createTables(db)
        org.qingjian.speech.learning.ai.memory.Conversations.createTables(db)
        org.qingjian.speech.learning.daily.PlanStore.createTables(db)
        org.qingjian.speech.learning.assessment.Store.createTables(db)
        org.qingjian.speech.learning.archive.Store.create(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS learner_model (word_id INTEGER NOT NULL, dimension TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, assisted INTEGER NOT NULL DEFAULT 0, independent_attempts INTEGER NOT NULL DEFAULT 0, independent_correct INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0, last_success_day TEXT, last_at INTEGER, next_due INTEGER, score REAL, origin TEXT NOT NULL, rule_version INTEGER NOT NULL, PRIMARY KEY (word_id, dimension))")
        db.execSQL("CREATE INDEX IF NOT EXISTS learner_due ON learner_model(next_due, state)")
        db.execSQL("CREATE TABLE IF NOT EXISTS user_overrides (word_id INTEGER NOT NULL, dimension TEXT NOT NULL, override_type TEXT NOT NULL, created_at INTEGER NOT NULL, valid_until INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, release_reason TEXT, released_at INTEGER, PRIMARY KEY (word_id, dimension))")
        db.execSQL("CREATE INDEX IF NOT EXISTS user_overrides_active ON user_overrides(active, valid_until)")
        db.execSQL("CREATE TABLE IF NOT EXISTS study_intents (id INTEGER PRIMARY KEY AUTOINCREMENT, intent_key TEXT NOT NULL UNIQUE, word_id INTEGER NOT NULL DEFAULT 0, english TEXT NOT NULL, target_dimension TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, scheduled_plan_id INTEGER NOT NULL DEFAULT 0, consumed_at INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS study_intents_status ON study_intents(status, expires_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS study_intents_word ON study_intents(word_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 10) org.qingjian.speech.learning.archive.Store.create(db)
        if(oldVersion < 6) org.qingjian.speech.learning.daily.PlanStore.createTables(db)
        if(oldVersion < 2) PracticeStore.createTables(db)
        if(oldVersion < 3) {
            // 工具操作依赖问题ID；删除对话后不能复用旧ID接收晚到结果。
            db.execSQL("CREATE TABLE messages_next (id INTEGER PRIMARY KEY AUTOINCREMENT,topic TEXT NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL)")
            db.execSQL("INSERT INTO messages_next SELECT id,topic,role,body,state FROM messages")
            db.execSQL("DROP TABLE messages")
            db.execSQL("ALTER TABLE messages_next RENAME TO messages")
            db.execSQL("CREATE INDEX messages_topic ON messages(topic,id)")
            org.qingjian.speech.learning.ai.tools.ActionStore.createTables(db)
        }
        if(oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to INTEGER")
            // 只有紧邻且已完成的问题能可靠回填；旧版孤立讲解保留原文。
            db.execSQL("UPDATE messages SET reply_to=(SELECT q.id FROM messages q WHERE q.topic=messages.topic AND q.id=(SELECT MAX(p.id) FROM messages p WHERE p.topic=messages.topic AND p.id<messages.id) AND q.role='user' AND q.state='done') WHERE role='assistant'")
            db.execSQL("CREATE UNIQUE INDEX messages_reply ON messages(reply_to) WHERE reply_to IS NOT NULL")
            org.qingjian.speech.learning.ai.memory.Conversations.createTables(db)
        }
        if(oldVersion < 5) {
            db.execSQL("CREATE TABLE words_next (id INTEGER PRIMARY KEY AUTOINCREMENT,lookup TEXT NOT NULL UNIQUE,english TEXT NOT NULL,meaning TEXT NOT NULL DEFAULT '',received INTEGER NOT NULL DEFAULT 0,played INTEGER NOT NULL DEFAULT 0,reviewed INTEGER NOT NULL DEFAULT 0,last_seen INTEGER NOT NULL)")
            db.execSQL("INSERT INTO words_next SELECT * FROM words")
            db.execSQL("DROP TABLE words")
            db.execSQL("ALTER TABLE words_next RENAME TO words")
            // v1/v2 迁移时刚创建的操作表已经包含新字段。
            val columns = db.rawQuery("PRAGMA table_info(learning_actions)", null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            if("automatic" !in columns) db.execSQL("ALTER TABLE learning_actions ADD COLUMN automatic INTEGER NOT NULL DEFAULT 0")
            if("created_word" !in columns) db.execSQL("ALTER TABLE learning_actions ADD COLUMN created_word INTEGER NOT NULL DEFAULT 0")
            if("word_snapshot" !in columns) db.execSQL("ALTER TABLE learning_actions ADD COLUMN word_snapshot TEXT NOT NULL DEFAULT ''")
        }
        if(oldVersion < 7) org.qingjian.speech.learning.notes.Store.createRevision(db)
        if(oldVersion < 8) org.qingjian.speech.learning.notes.Store.createMaterials(db)
        if(oldVersion < 9) {
            val columns=db.rawQuery("PRAGMA table_info(oral_recordings)",null).use {c->buildSet {while(c.moveToNext())add(c.getString(1))}}
            if("reference_text" !in columns)db.execSQL("ALTER TABLE oral_recordings ADD COLUMN reference_text TEXT NOT NULL DEFAULT ''")
            if("target_mode" !in columns)db.execSQL("ALTER TABLE oral_recordings ADD COLUMN target_mode TEXT NOT NULL DEFAULT 'word'")
            db.execSQL("UPDATE oral_recordings SET reference_text=english WHERE reference_text=''")
            org.qingjian.speech.learning.assessment.Store.createTables(db)
        }
        if(oldVersion<10)org.qingjian.speech.learning.archive.Store.migrateAssessments(db)
        if(oldVersion < 11) {
            db.execSQL("CREATE TABLE IF NOT EXISTS learner_model (word_id INTEGER NOT NULL, dimension TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0, assisted INTEGER NOT NULL DEFAULT 0, independent_attempts INTEGER NOT NULL DEFAULT 0, independent_correct INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0, last_success_day TEXT, last_at INTEGER, next_due INTEGER, score REAL, origin TEXT NOT NULL, rule_version INTEGER NOT NULL, PRIMARY KEY (word_id, dimension))")
            db.execSQL("CREATE INDEX IF NOT EXISTS learner_due ON learner_model(next_due, state)")
            db.execSQL("CREATE TABLE IF NOT EXISTS user_overrides (word_id INTEGER NOT NULL, dimension TEXT NOT NULL, override_type TEXT NOT NULL, created_at INTEGER NOT NULL, valid_until INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, release_reason TEXT, released_at INTEGER, PRIMARY KEY (word_id, dimension))")
            db.execSQL("CREATE INDEX IF NOT EXISTS user_overrides_active ON user_overrides(active, valid_until)")

            // 重构 study_plans：移除 day UNIQUE，支持 (day, round_no) 唯一
            db.execSQL("CREATE TABLE study_plans_v11 (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, round_no INTEGER NOT NULL DEFAULT 0, format_version INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL DEFAULT 'READY', title TEXT NOT NULL, reason TEXT NOT NULL, phase TEXT NOT NULL DEFAULT 'LEARN', cursor INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0, adjustment TEXT NOT NULL DEFAULT '', reflection TEXT NOT NULL DEFAULT '', planned_credit INTEGER NOT NULL DEFAULT 0, done_credit INTEGER NOT NULL DEFAULT 0, active_ms INTEGER NOT NULL DEFAULT 0, measurement_valid INTEGER NOT NULL DEFAULT 0, preferences_revision INTEGER NOT NULL DEFAULT 0, rule_version INTEGER NOT NULL DEFAULT 1, plan_revision_no INTEGER NOT NULL DEFAULT 0, budget_snapshot TEXT NOT NULL DEFAULT '{}', updated INTEGER NOT NULL, UNIQUE(day, round_no))")
            db.execSQL("INSERT INTO study_plans_v11 (id, day, round_no, format_version, status, title, reason, phase, cursor, revision, adjustment, reflection, updated) SELECT id, day, 0, 1, (CASE WHEN phase = 'DONE' THEN 'DONE' ELSE 'PAUSED' END), title, reason, phase, cursor, revision, adjustment, reflection, updated FROM study_plans")
            db.execSQL("DROP TABLE study_plans")
            db.execSQL("ALTER TABLE study_plans_v11 RENAME TO study_plans")
            db.execSQL("CREATE INDEX study_plans_day_round ON study_plans(day, round_no)")
            db.execSQL("CREATE INDEX study_plans_status ON study_plans(status, format_version)")

            // 扩充 study_items 字段
            val itemCols = db.rawQuery("PRAGMA table_info(study_items)", null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            if("reason_code" !in itemCols) db.execSQL("ALTER TABLE study_items ADD COLUMN reason_code TEXT NOT NULL DEFAULT ''")
            if("reason_text" !in itemCols) db.execSQL("ALTER TABLE study_items ADD COLUMN reason_text TEXT NOT NULL DEFAULT ''")
            if("item_key" !in itemCols) db.execSQL("ALTER TABLE study_items ADD COLUMN item_key TEXT NOT NULL DEFAULT ''")

            // 创建 study_item_stages 与 plan_revisions
            db.execSQL("CREATE TABLE IF NOT EXISTS study_item_stages (stage_key TEXT PRIMARY KEY, plan_id INTEGER NOT NULL, item_id INTEGER NOT NULL, word_id INTEGER NOT NULL, position INTEGER NOT NULL, dimension TEXT NOT NULL, stage_type TEXT NOT NULL, snapshot TEXT NOT NULL DEFAULT '', credit INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL DEFAULT 'pending', draft TEXT NOT NULL DEFAULT '', assisted INTEGER NOT NULL DEFAULT 0, result_event_key TEXT, active_ms INTEGER NOT NULL DEFAULT 0, measurement_valid INTEGER NOT NULL DEFAULT 1, updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS stages_plan_pos ON study_item_stages(plan_id, position)")
            db.execSQL("CREATE INDEX IF NOT EXISTS stages_word ON study_item_stages(word_id)")

            db.execSQL("CREATE TABLE IF NOT EXISTS plan_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT, plan_id INTEGER NOT NULL, revision_no INTEGER NOT NULL, action TEXT NOT NULL, before_snapshot TEXT NOT NULL DEFAULT '', after_snapshot TEXT NOT NULL DEFAULT '', user_constraints TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, UNIQUE(plan_id, revision_no))")
            db.execSQL("CREATE INDEX IF NOT EXISTS plan_revisions_plan ON plan_revisions(plan_id, revision_no)")
        }
        if(oldVersion < 12) {
            val columns = db.rawQuery("PRAGMA table_info(words)", null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            if("gloss_origin" !in columns) {
                db.execSQL("ALTER TABLE words ADD COLUMN gloss_origin TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("UPDATE words SET gloss_origin='desktop-vocab' WHERE id IN (SELECT word_id FROM desktop_sources)")
            }
        }
        if(oldVersion < 13) {
            db.execSQL("CREATE TABLE IF NOT EXISTS study_intents (id INTEGER PRIMARY KEY AUTOINCREMENT, intent_key TEXT NOT NULL UNIQUE, word_id INTEGER NOT NULL DEFAULT 0, english TEXT NOT NULL, target_dimension TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, scheduled_plan_id INTEGER NOT NULL DEFAULT 0, consumed_at INTEGER NOT NULL DEFAULT 0, priority INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS study_intents_status ON study_intents(status, expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS study_intents_word ON study_intents(word_id)")
        }
    }

    fun record(text: String, completed: Boolean = false) {
        val word = text.trim().replace(Regex("\\s+"), " ")
        if (word.isEmpty() || word.length > 500) return
        val key = word.lowercase(Locale.ROOT)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("lookup", key); put("english", word); put("last_seen", System.currentTimeMillis())
            }
            db.insertWithOnConflict("words", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val column = if (completed) "played" else "received"
            db.execSQL("UPDATE words SET $column=$column+1, last_seen=? WHERE lookup=?", arrayOf<Any>(System.currentTimeMillis(), key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun words(query: String = ""): List<VocabularyEntry> {
        val result = mutableListOf<VocabularyEntry>()
        // instr 将搜索内容按普通文字匹配，百分号和下划线不会成为通配符。
        readableDatabase.rawQuery("SELECT id, english, meaning, received, played, reviewed, last_seen, gloss_origin FROM words WHERE instr(lower(english), lower(?))>0 OR instr(meaning, ?)>0 ORDER BY last_seen DESC LIMIT 500", arrayOf(query, query)).use { c ->
            while (c.moveToNext()) result += VocabularyEntry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getLong(6), c.getString(7))
        }
        return result
    }
    fun allWords():List<VocabularyEntry> = readableDatabase.rawQuery("SELECT id,english,meaning,received,played,reviewed,last_seen,gloss_origin FROM words ORDER BY last_seen DESC,id DESC",null).use {c->buildList {while(c.moveToNext())add(VocabularyEntry(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3),c.getInt(4),c.getInt(5),c.getLong(6),c.getString(7)))}}

    fun word(id: Long): VocabularyEntry? = readableDatabase.rawQuery("SELECT id, english, meaning, received, played, reviewed, last_seen, gloss_origin FROM words WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) VocabularyEntry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getLong(6), c.getString(7)) else null
    }

    fun setMeaning(id: Long, meaning: String) {
        require(meaning.length <= 1000)
        writableDatabase.update("words", ContentValues().apply { put("meaning", meaning.trim()) }, "id=?", arrayOf(id.toString()))
    }

    fun setGlossOrigin(id: Long, origin: String) {
        require(origin in listOf("desktop-vocab", "static-table", "ai", "unknown"))
        writableDatabase.update("words", ContentValues().apply { put("gloss_origin", origin) }, "id=?", arrayOf(id.toString()))
    }

    fun addWord(text: String, origin: String = "unknown"): Long {
        val word = text.trim().replace(Regex("\\s+"), " ")
        require(word.isNotBlank() && word.length <= 500)
        require(origin in listOf("desktop-vocab", "static-table", "ai", "unknown"))
        val key = word.lowercase(Locale.ROOT)
        writableDatabase.insertWithOnConflict("words", null, ContentValues().apply {
            put("lookup", key); put("english", word); put("last_seen", System.currentTimeMillis())
            put("gloss_origin", origin)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return readableDatabase.rawQuery("SELECT id FROM words WHERE lookup=?", arrayOf(key)).use { c -> check(c.moveToFirst()); c.getLong(0) }
    }

    fun reviewed(id: Long) = writableDatabase.execSQL("UPDATE words SET reviewed=reviewed+1 WHERE id=?", arrayOf(id))

    fun messages(topic: String): List<ChatEntry> {
        val result = mutableListOf<ChatEntry>()
        readableDatabase.rawQuery("SELECT id, role, body, state,reply_to FROM (SELECT id, role, body, state,reply_to FROM messages WHERE topic=? ORDER BY id DESC LIMIT 100) ORDER BY id", arrayOf(topic)).use { c ->
            while(c.moveToNext()) result += ChatEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), if(c.isNull(4)) null else c.getLong(4))
        }
        return result
    }

    fun question(topic: String, text: String): Long {
        org.qingjian.speech.learning.ai.memory.Conversations(app).ensure(topic, topic.removePrefix("word:").toLongOrNull() ?: 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("messages", null, ContentValues().apply {
                put("topic", topic); put("role", "user"); put("body", text); put("state", "pending")
            })
            db.execSQL("UPDATE conversations SET updated=? WHERE topic=?", arrayOf<Any>(System.currentTimeMillis(), topic))
            db.setTransactionSuccessful(); return id
        } finally { db.endTransaction() }
    }

    fun answer(topic: String, questionId: Long, text: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.update("messages", ContentValues().apply { put("state", "done") }, "id=? AND topic=? AND role='user' AND state!='done'", arrayOf(questionId.toString(), topic))
            // 用户清空学习记录时，晚到的回答不能重新建立已删除的对话。
            if(existing > 0) db.insertOrThrow("messages", null, ContentValues().apply {
                    put("topic", topic); put("role", "assistant"); put("body", text); put("state", "done"); put("reply_to", questionId)
                })
            db.setTransactionSuccessful()
            return existing > 0
        } finally { db.endTransaction() }
    }

    fun failed(id: Long) = writableDatabase.execSQL("UPDATE messages SET state='failed' WHERE id=? AND role='user' AND state!='done'", arrayOf(id))

    fun clearMessages(topic: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("learning_actions", "topic=? AND state!=?", arrayOf(topic, "applied"))
            db.update("learning_actions", ContentValues().apply { put("topic", "saved:$topic") }, "topic=? AND state=?", arrayOf(topic, "applied"))
            db.delete("messages", "topic=?", arrayOf(topic))
            db.execSQL("UPDATE conversations SET summary='',through_id=0,revision=revision+1 WHERE topic=?", arrayOf(topic))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        org.qingjian.speech.learning.ai.memory.Conversations(app).saveDraft(topic, "")
    }

    fun clearLearning() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for(table in listOf("learning_events","independent_exams","learner_memory","ai_usage","history_analysis","desktop_sources","learner_model","user_overrides","study_intents"))db.delete(table,null,null)
            db.execSQL("UPDATE archive_meta SET revision=revision+1 WHERE id=1")
            db.delete("word_materials", null, null)
            db.delete("oral_assessments",null,null);db.delete("word_workspaces",null,null)
            db.delete("study_item_stages", null, null); db.delete("plan_revisions", null, null)
            db.delete("study_questions", null, null); db.delete("study_items", null, null)
            db.delete("study_plans", null, null); db.delete("study_reviews", null, null)
            db.execSQL("UPDATE study_meta SET epoch=epoch+1 WHERE id=1")
            db.delete("learning_actions", null, null)
            db.delete("conversations", null, null)
            db.delete("quiz_attempts", null, null); db.delete("oral_recordings", null, null)
            db.delete("messages", null, null); db.delete("words", null, null); db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
        val conversationPrefs = app.getSharedPreferences("learning_conversations", Context.MODE_PRIVATE)
        conversationPrefs.edit().apply { conversationPrefs.all.keys.filter { it.startsWith("draft:") || it.startsWith("selected:") }.forEach { remove(it) } }.apply()
        AudioFiles.clear(app)
        app.getSharedPreferences("spelling_drafts",0).edit().clear().apply()
    }

    companion object {
        @Volatile private var instance: LearningStore? = null

        fun get(context: Context): LearningStore = instance ?: synchronized(this) {
            instance ?: LearningStore(context.applicationContext).also { instance = it }
        }
    }
}
