//! 本地学习资料与会话仓库；第一阶段复用系统 SQLite，不引入额外运行依赖。
package org.qingjian.speech.learning

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.oral.AudioFiles

class LearningStore private constructor(private val app: Context) : SQLiteOpenHelper(app, "learning.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE words (id INTEGER PRIMARY KEY, lookup TEXT NOT NULL UNIQUE, english TEXT NOT NULL, meaning TEXT NOT NULL DEFAULT '', received INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, reviewed INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, topic TEXT NOT NULL, role TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE INDEX messages_topic ON messages(topic, id)")
        PracticeStore.createTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion < 2) PracticeStore.createTables(db)
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
            db.execSQL("UPDATE words SET $column=$column+1, last_seen=? WHERE lookup=?", arrayOf(System.currentTimeMillis(), key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun words(query: String = ""): List<VocabularyEntry> {
        val result = mutableListOf<VocabularyEntry>()
        // instr 将搜索内容按普通文字匹配，百分号和下划线不会成为通配符。
        readableDatabase.rawQuery("SELECT id, english, meaning, received, played, reviewed, last_seen FROM words WHERE instr(lower(english), lower(?))>0 OR instr(meaning, ?)>0 ORDER BY last_seen DESC LIMIT 500", arrayOf(query, query)).use { c ->
            while (c.moveToNext()) result += VocabularyEntry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getLong(6))
        }
        return result
    }

    fun word(id: Long): VocabularyEntry? = readableDatabase.rawQuery("SELECT id, english, meaning, received, played, reviewed, last_seen FROM words WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) VocabularyEntry(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getLong(6)) else null
    }

    fun setMeaning(id: Long, meaning: String) {
        require(meaning.length <= 1000)
        writableDatabase.update("words", ContentValues().apply { put("meaning", meaning.trim()) }, "id=?", arrayOf(id.toString()))
    }

    fun addWord(text: String): Long {
        val word = text.trim().replace(Regex("\\s+"), " ")
        require(word.isNotBlank() && word.length <= 500)
        val key = word.lowercase(Locale.ROOT)
        writableDatabase.insertWithOnConflict("words", null, ContentValues().apply {
            put("lookup", key); put("english", word); put("last_seen", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return readableDatabase.rawQuery("SELECT id FROM words WHERE lookup=?", arrayOf(key)).use { c -> check(c.moveToFirst()); c.getLong(0) }
    }

    fun reviewed(id: Long) = writableDatabase.execSQL("UPDATE words SET reviewed=reviewed+1 WHERE id=?", arrayOf(id))

    fun messages(topic: String): List<ChatEntry> {
        val result = mutableListOf<ChatEntry>()
        readableDatabase.rawQuery("SELECT id, role, body, state FROM (SELECT id, role, body, state FROM messages WHERE topic=? ORDER BY id DESC LIMIT 100) ORDER BY id", arrayOf(topic)).use { c ->
            while(c.moveToNext()) result += ChatEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
        }
        return result
    }

    fun question(topic: String, text: String): Long = writableDatabase.insertOrThrow("messages", null, ContentValues().apply {
        put("topic", topic); put("role", "user"); put("body", text); put("state", "pending")
    })

    fun answer(topic: String, questionId: Long, text: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.update("messages", ContentValues().apply { put("state", "done") }, "id=? AND topic=?", arrayOf(questionId.toString(), topic))
            // 用户清空学习记录时，晚到的回答不能重新建立已删除的对话。
            if(existing > 0) db.insertOrThrow("messages", null, ContentValues().apply {
                    put("topic", topic); put("role", "assistant"); put("body", text); put("state", "done")
                })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun failed(id: Long) = writableDatabase.execSQL("UPDATE messages SET state='failed' WHERE id=?", arrayOf(id))

    fun clearMessages(topic: String) = writableDatabase.delete("messages", "topic=?", arrayOf(topic))

    fun clearLearning() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("quiz_attempts", null, null); db.delete("oral_recordings", null, null)
            db.delete("messages", null, null); db.delete("words", null, null); db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
        AudioFiles.clear(app)
    }

    companion object {
        @Volatile private var instance: LearningStore? = null

        fun get(context: Context): LearningStore = instance ?: synchronized(this) {
            instance ?: LearningStore(context.applicationContext).also { instance = it }
        }
    }
}
