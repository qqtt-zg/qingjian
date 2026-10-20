//! 会话目录与摘要检查点；用修订号和问题存在性拒绝清空后的晚到摘要。
package org.qingjian.speech.learning.ai.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.qingjian.speech.learning.ChatEntry
import org.qingjian.speech.learning.LearningStore
import java.util.UUID

class Conversations(context: Context) {
    private val store = LearningStore.get(context)
    private val prefs = context.getSharedPreferences("learning_conversations", Context.MODE_PRIVATE)

    var automatic: Boolean
        get() = prefs.getBoolean("automatic_summary", true)
        set(value) { prefs.edit().putBoolean("automatic_summary", value).apply() }

    fun ensure(topic: String, wordId: Long): Conversation {
        val db = store.writableDatabase
        db.insertWithOnConflict("conversations", null, ContentValues().apply {
            put("topic", topic); put("word_id", wordId)
            put("title", if(wordId > 0) "单词学习" else "日常英语")
            put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        return checkNotNull(get(topic))
    }

    fun get(topic: String): Conversation? = store.readableDatabase.rawQuery(
        "SELECT topic,word_id,title,summary,through_id,revision FROM conversations WHERE topic=?", arrayOf(topic)).use { c ->
        if(c.moveToFirst()) entry(c) else null
    }

    fun list(wordId: Long): List<Conversation> = store.readableDatabase.rawQuery(
        "SELECT topic,word_id,title,summary,through_id,revision FROM conversations WHERE word_id=? ORDER BY updated DESC, rowid DESC", arrayOf(wordId.toString())).use { c ->
        buildList { while(c.moveToNext()) add(entry(c)) }
    }

    fun selected(wordId: Long): String {
        val last = prefs.getString("selected:$wordId", "").orEmpty()
        return if(get(last)?.wordId == wordId) last else if(wordId > 0) "word:$wordId" else "general"
    }

    fun select(topic: String) { get(topic)?.let { prefs.edit().putString("selected:${it.wordId}", topic).apply() } }

    fun create(wordId: Long): Conversation {
        val value = ensure("chat:${UUID.randomUUID()}", wordId)
        rename(value.topic, "新对话 " + android.text.format.DateFormat.format("MM-dd HH:mm", java.util.Date()))
        select(value.topic)
        return checkNotNull(get(value.topic))
    }

    fun draft(topic: String): String = prefs.getString("draft:$topic", "").orEmpty().take(4000)

    fun saveDraft(topic: String, text: String) {
        prefs.edit().apply { if(get(topic) == null) remove("draft:$topic") else putString("draft:$topic", text.take(4000)) }.apply()
    }

    fun rename(topic: String, title: String) {
        require(title.isNotBlank() && title.length <= 40)
        store.writableDatabase.update("conversations", ContentValues().apply { put("title", title.trim()) }, "topic=?", arrayOf(topic))
    }

    fun entries(topic: String, after: Long, before: Long): List<ChatEntry> = readHistory(topic, after, before, false)

    fun recent(topic: String, after: Long, before: Long): List<ChatEntry> = readHistory(topic, after, before, true)

    private fun readHistory(topic: String, after: Long, before: Long, recent: Boolean): List<ChatEntry> {
        val selected = store.readableDatabase.rawQuery(
            "SELECT id,role,body,state,reply_to FROM messages WHERE topic=? AND id>? AND id<? ORDER BY id ${if(recent) "DESC LIMIT 100" else "LIMIT 400"}",
            arrayOf(topic, after.toString(), before.toString())).use { c -> buildList { while(c.moveToNext()) add(message(c)) } }
        val ids = selected.map { it.id }.toSet()
        // 早先的待问在很久后重试，问题可能已落在摘要检查点之前，需要按回复关系补齐。
        val parents = selected.mapNotNull { it.replyTo }.filterNot { it in ids }.distinct()
        if(parents.isEmpty()) return selected.sortedBy { it.id }
        val missing = store.readableDatabase.rawQuery(
            "SELECT id,role,body,state,reply_to FROM messages WHERE topic=? AND id IN (${parents.joinToString(",") { "?" }})",
            (listOf(topic) + parents.map { it.toString() }).toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(message(c)) } }
        return (selected + missing).sortedBy { it.id }
    }

    private fun message(c: Cursor) = ChatEntry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), if(c.isNull(4)) null else c.getLong(4))

    fun saveSummary(previous: Conversation, through: Long, text: String, question: Long): Boolean {
        require(text.isNotBlank() && text.length <= 3000 && through > previous.through)
        val changed = store.writableDatabase.update("conversations", ContentValues().apply {
            put("summary", text); put("through_id", through); put("revision", previous.revision + 1)
        }, "topic=? AND revision=? AND EXISTS(SELECT 1 FROM messages WHERE id=? AND topic=?)",
            arrayOf(previous.topic, previous.revision.toString(), question.toString(), previous.topic))
        return changed == 1
    }

    fun forgetSummary(topic: String) = store.writableDatabase.execSQL(
        "UPDATE conversations SET summary='',through_id=0,revision=revision+1 WHERE topic=?", arrayOf(topic))

    fun latestAnswer(wordId: Long): Pair<String, ChatEntry>? = store.readableDatabase.rawQuery(
        "SELECT m.topic,m.id,m.role,m.body,m.state FROM messages m JOIN conversations c ON c.topic=m.topic WHERE c.word_id=? AND m.role='assistant' AND m.state='done' ORDER BY m.id DESC LIMIT 1",
        arrayOf(wordId.toString())).use { c ->
        if(c.moveToFirst()) c.getString(0) to ChatEntry(c.getLong(1), c.getString(2), c.getString(3), c.getString(4)) else null
    }

    private fun entry(c: Cursor) = Conversation(c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))

    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE conversations (topic TEXT PRIMARY KEY,word_id INTEGER NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL DEFAULT '',through_id INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX conversations_word ON conversations(word_id,updated)")
            db.execSQL("INSERT INTO conversations(topic,word_id,title,updated) SELECT topic,CASE WHEN topic LIKE 'word:%' THEN CAST(substr(topic,6) AS INTEGER) ELSE 0 END,CASE WHEN topic LIKE 'word:%' THEN '单词学习' ELSE '此前的对话' END,MAX(id) FROM messages GROUP BY topic")
        }
    }
}
