//! 练习结果复用学习数据库，唯一题目标识防止重复提交计分。
package org.qingjian.speech.learning.practice

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.Recording
import org.qingjian.speech.learning.quiz.Attempt
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.Question
import java.io.File

class PracticeStore(context: Context) {
    private val app = context.applicationContext
    private val store = LearningStore.get(app)
    private val columns = "question_key, word_id, english, meaning, kind, answer, correct"

    fun answer(key: String): Attempt? = store.readableDatabase.rawQuery("SELECT $columns FROM quiz_attempts WHERE question_key=?", arrayOf(key)).use { c ->
        if(c.moveToFirst()) attempt(c) else null
    }

    private fun attempt(c: Cursor) = Attempt(c.getString(0), c.getLong(1), c.getString(2), c.getString(3), Kind.valueOf(c.getString(4)), c.getString(5), c.getInt(6) == 1)

    fun submit(question: Question, text: String): Attempt {
        require(text.isNotBlank() && text.length <= 1000)
        if(question.kind != Kind.SPELLING) require(text in question.options)
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            require(Question.normalize(store.word(question.wordId)?.english.orEmpty()) == Question.normalize(question.english)) { "词汇已删除，请重新开始测验" }
            val values = ContentValues().apply {
                put("question_key", question.key); put("word_id", question.wordId); put("english", question.english)
                put("meaning", question.meaning); put("kind", question.kind.name); put("answer", text.trim())
                put("correct", if(question.correct(text)) 1 else 0); put("created", System.currentTimeMillis())
            }
            db.insertWithOnConflict("quiz_attempts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val result = checkNotNull(answer(question.key))
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    fun summary(kind: Kind): Pair<Int, Int> = store.readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(correct),0) FROM quiz_attempts WHERE kind=?", arrayOf(kind.name)).use { c -> c.moveToFirst(); c.getInt(0) to c.getInt(1) }

    fun mistakes(): List<Attempt> = store.readableDatabase.rawQuery(
        "SELECT $columns FROM quiz_attempts WHERE id IN (SELECT MAX(id) FROM quiz_attempts GROUP BY word_id, kind) AND correct=0 AND word_id IN (SELECT id FROM words) ORDER BY id DESC LIMIT 50", null).use { c ->
        buildList { while(c.moveToNext()) add(attempt(c)) }
    }

    fun saveRecording(wordId: Long, english: String, source: File, duration: Long, reference: String=english, mode: String="word"): Long {
        require(duration in 500..30000)
        org.qingjian.speech.learning.assessment.Protocol.validate(reference,mode)
        val db = store.writableDatabase
        var saved: File? = null
        db.beginTransaction()
        try {
            require(Question.normalize(store.word(wordId)?.english.orEmpty()) == Question.normalize(english)) { "词汇已删除，本次录音未保存" }
            val destination = AudioFiles.save(app, source)
            saved = destination
            val id = db.insertOrThrow("oral_recordings", null, ContentValues().apply {
                put("word_id", wordId); put("english", english); put("filename", destination.name); put("duration", duration)
                put("rating", "unreviewed"); put("created", System.currentTimeMillis())
                put("reference_text",reference);put("target_mode",mode)
            })
            db.setTransactionSuccessful()
            return id
        } catch(error: Exception) { saved?.delete(); throw error }
        finally { db.endTransaction() }
    }

    fun recordings(wordId: Long): List<Recording> = store.readableDatabase.rawQuery(
        "SELECT id, word_id, english, filename, duration, rating,reference_text,target_mode FROM oral_recordings WHERE word_id=? ORDER BY id DESC LIMIT 20", arrayOf(wordId.toString())).use { c ->
        buildList { while(c.moveToNext()) add(Recording(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5),c.getString(6).ifBlank {c.getString(2)},c.getString(7))) }
    }

    fun rateRecording(id: Long, rating: String) {
        require(rating in listOf("again", "satisfied"))
        store.writableDatabase.update("oral_recordings", ContentValues().apply { put("rating", rating) }, "id=?", arrayOf(id.toString()))
    }

    fun deleteRecording(recording: Recording) {
        store.writableDatabase.delete("oral_recordings", "id=? AND filename=?", arrayOf(recording.id.toString(), recording.filename))
        AudioFiles.file(app, recording.filename).delete()
    }

    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE quiz_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT, question_key TEXT NOT NULL UNIQUE, word_id INTEGER NOT NULL, english TEXT NOT NULL, meaning TEXT NOT NULL, kind TEXT NOT NULL, answer TEXT NOT NULL, correct INTEGER NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX quiz_word_kind ON quiz_attempts(word_id,kind,id)")
            db.execSQL("CREATE TABLE oral_recordings (id INTEGER PRIMARY KEY AUTOINCREMENT, word_id INTEGER NOT NULL, english TEXT NOT NULL, filename TEXT NOT NULL UNIQUE, duration INTEGER NOT NULL, rating TEXT NOT NULL, created INTEGER NOT NULL,reference_text TEXT NOT NULL DEFAULT '',target_mode TEXT NOT NULL DEFAULT 'word')")
        }
    }
}
