//! 真实评测记录的持久化；绑定音频哈希、录音与词条，拒绝删除后的晚到结果。
package org.qingjian.speech.learning.assessment

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.Recording
import java.security.MessageDigest
import java.util.UUID

class Store(private val app: Context) {
    private val book=LearningStore.get(app)
    fun start(recording: Recording): Attempt {
        val db=book.writableDatabase;db.beginTransaction()
        try {
            require(book.word(recording.wordId)?.english==recording.english) {"词条已删除或变化，未提交评测"}
            val valid=db.rawQuery("SELECT 1 FROM oral_recordings WHERE id=? AND filename=?",arrayOf(recording.id.toString(),recording.filename)).use {it.moveToFirst()}
            require(valid) {"录音已删除，未提交评测"}
            val file=AudioFiles.file(app,recording.filename);Protocol.pcm(file)
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
            val id=UUID.randomUUID().toString();val created=System.currentTimeMillis()
            db.insertOrThrow("oral_assessments",null,ContentValues().apply {
                put("id",id);put("recording_id",recording.id);put("word_id",recording.wordId);put("word_snapshot",recording.english)
                put("reference_text",recording.reference);put("mode",recording.mode);put("audio_hash",hash);put("state","running");put("created",created)
            })
            org.qingjian.speech.learning.archive.Store(app).event(recording.wordId,recording.english,"发音评测",JSONObject().put("state","running"),"assessment:$id")
            db.setTransactionSuccessful()
            return Attempt(id,recording.id,recording.reference,recording.mode,"running",null,"",created)
        } finally {db.endTransaction()}
    }
    fun finish(id: String,result: Result): Boolean {
        val db=book.writableDatabase;db.beginTransaction()
        try {
            val row=db.rawQuery("SELECT a.recording_id,a.audio_hash,r.filename,a.word_snapshot,w.english FROM oral_assessments a JOIN oral_recordings r ON r.id=a.recording_id JOIN words w ON w.id=a.word_id WHERE a.id=? AND a.state='running'",arrayOf(id)).use {c->
                if(!c.moveToFirst())null else listOf(c.getString(1),c.getString(2),c.getString(3),c.getString(4))
            } ?: return false
            if(row[2]!=row[3])return false
            val file=AudioFiles.file(app,row[1]);if(!file.isFile)return false
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
            if(hash!=row[0])return false
            val updated=db.update("oral_assessments",ContentValues().apply {put("state","succeeded");put("result",result.json().toString());put("notice","")},"id=? AND state='running'",arrayOf(id))
            if(updated==1)db.execSQL("UPDATE learning_events SET payload=? WHERE event_key=?",arrayOf(JSONObject().put("state","succeeded").put("result",result.json()).toString(),"assessment:$id"))
            db.setTransactionSuccessful();return updated==1
        } finally {db.endTransaction()}
    }
    fun fail(id: String,notice: String,cancelled: Boolean=false) {val state=if(cancelled)"cancelled" else "failed";val changed=book.writableDatabase.update("oral_assessments",ContentValues().apply {put("state",state);put("notice",notice.take(200))},"id=? AND state='running'",arrayOf(id));if(changed==1)book.writableDatabase.execSQL("UPDATE learning_events SET payload=? WHERE event_key=?",arrayOf(JSONObject().put("state",state).toString(),"assessment:$id"))}
    fun latest(recordingId: Long): Attempt?=book.readableDatabase.rawQuery("SELECT id,reference_text,mode,state,result,notice,created FROM oral_assessments WHERE recording_id=? ORDER BY created DESC,rowid DESC LIMIT 1",arrayOf(recordingId.toString())).use {c->
        if(!c.moveToFirst())null else Attempt(c.getString(0),recordingId,c.getString(1),c.getString(2),c.getString(3),if(c.getString(4).isBlank())null else Result.from(JSONObject(c.getString(4))),c.getString(5),c.getLong(6))
    }
    fun history(recordingId:Long):List<Attempt> = book.readableDatabase.rawQuery("SELECT id,reference_text,mode,state,result,notice,created FROM oral_assessments WHERE recording_id=? AND state='succeeded' ORDER BY created DESC,rowid DESC LIMIT 10",arrayOf(recordingId.toString())).use {c->buildList {
        while(c.moveToNext())add(Attempt(c.getString(0),recordingId,c.getString(1),c.getString(2),c.getString(3),Result.from(JSONObject(c.getString(4))),c.getString(5),c.getLong(6)))
    }}
    fun facts(wordId: Long=0): JSONObject {
        val rows=book.readableDatabase.rawQuery("SELECT a.reference_text,a.mode,a.result,a.created,a.word_id FROM oral_assessments a JOIN oral_recordings r ON r.id=a.recording_id JOIN words w ON w.id=a.word_id WHERE a.state='succeeded' AND (?=0 OR a.word_id=?) ORDER BY a.created DESC LIMIT 5",arrayOf(wordId.toString(),wordId.toString())).use {c->buildList {
            while(c.moveToNext())add(JSONObject().put("word_id",c.getLong(4)).put("reference",c.getString(0)).put("mode",c.getString(1)).put("provider","xfyun").put("result",JSONObject(c.getString(2))).put("created",c.getLong(3)))
        }}
        return JSONObject().put("recent_assessments",JSONArray(rows)).put("note","仅为已成功保存的讯飞评分，不是AI评分；缺少结果表示未评测，自评与掌握程度另计。")
    }
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE word_workspaces (word_id INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE oral_assessments (id TEXT PRIMARY KEY,recording_id INTEGER NOT NULL,word_id INTEGER NOT NULL,word_snapshot TEXT NOT NULL,reference_text TEXT NOT NULL,mode TEXT NOT NULL,audio_hash TEXT NOT NULL,state TEXT NOT NULL,result TEXT NOT NULL DEFAULT '',notice TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX assessments_recording ON oral_assessments(recording_id,created)")
            db.execSQL("CREATE TRIGGER assessment_recording_cleanup AFTER DELETE ON oral_recordings BEGIN DELETE FROM oral_assessments WHERE recording_id=OLD.id; END")
            db.execSQL("CREATE TRIGGER assessment_word_cleanup AFTER DELETE ON words BEGIN DELETE FROM oral_assessments WHERE word_id=OLD.id; DELETE FROM word_workspaces WHERE word_id=OLD.id; END")
        }
    }
}
