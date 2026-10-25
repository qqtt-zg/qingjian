//! 学习资料整包的版本检查与事务保存，保留旧词卡并隔离撤销范围。
package org.qingjian.speech.learning.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore

class Store(context: Context) {
    private val book=LearningStore.get(context)

    fun snapshot(id: Long): Snapshot? {
        val db=book.readableDatabase
        val base=db.rawQuery("SELECT english,meaning,note_revision,gloss_origin FROM words WHERE id=?",arrayOf(id.toString())).use { c ->
            if(c.moveToFirst()) Snapshot(id,c.getString(0),c.getString(1),c.getLong(2), glossOrigin = c.getString(3)) else null
        } ?: return null
        val materials=db.rawQuery("SELECT payload FROM word_materials WHERE word_id=?",arrayOf(id.toString())).use {if(it.moveToFirst())it.getString(0) else ""}
        val legacy=db.rawQuery("SELECT payload FROM learning_actions WHERE word_id=? AND kind='prepare_word_card' AND state='applied' ORDER BY id DESC LIMIT 1",arrayOf(id.toString())).use {if(it.moveToFirst())it.getString(0) else ""}
        return base.copy(materials=materials,legacy=legacy)
    }

    fun card(id: Long): JSONObject? {
        val before=snapshot(id) ?: return null
        return before.content()?.put("english",before.english)?.put("meaning",before.meaning)
    }

    fun complete(id: Long): Boolean {
        val before=snapshot(id) ?: return false
        val data=before.content() ?: return false
        return before.meaning.isNotBlank() && data.optString("example").isNotBlank() && data.optString("translation").isNotBlank() &&
            data.optString("usage").isNotBlank() && (data.optJSONArray("collocations")?.length() ?: 0)>0
    }

    fun apply(before: Snapshot, pack: Pack): Snapshot? {
        val db=book.writableDatabase;db.beginTransaction()
        try {
            if(snapshot(before.id)!=before) return null
            val merged=pack.merge(before)
            check(db.update("words",ContentValues().apply {put("meaning",merged.meaning)},"id=?",arrayOf(before.id.toString()))==1)
            write(db,before.id,merged.json().toString())
            val result=snapshot(before.id);db.setTransactionSuccessful();return result
        } finally {db.endTransaction()}
    }

    fun undo(before: Snapshot, applied: Snapshot): Snapshot? {
        val db=book.writableDatabase;db.beginTransaction()
        try {
            if(snapshot(applied.id)!=applied) return null
            check(db.update("words",ContentValues().apply {put("meaning",before.meaning)},"id=?",arrayOf(before.id.toString()))==1)
            write(db,before.id,before.materials)
            val result=snapshot(before.id);db.setTransactionSuccessful();return result
        } finally {db.endTransaction()}
    }

    private fun write(db: SQLiteDatabase,id: Long,payload: String) {
        if(payload.isEmpty()) db.delete("word_materials","word_id=?",arrayOf(id.toString()))
        else check(db.insertWithOnConflict("word_materials",null,ContentValues().apply {put("word_id",id);put("payload",payload)},SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
    }

    companion object {
        fun createMaterials(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE word_materials (word_id INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE TRIGGER words_material_cleanup AFTER DELETE ON words BEGIN DELETE FROM word_materials WHERE word_id=OLD.id; END")
        }

        fun createRevision(db: SQLiteDatabase) {
            val cols = db.rawQuery("PRAGMA table_info(words)", null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
            if ("note_revision" !in cols) {
                db.execSQL("ALTER TABLE words ADD COLUMN note_revision INTEGER NOT NULL DEFAULT 0")
            }
            // 同值保存也计入手动编辑，防止改过又清空后被旧请求写回。
            db.execSQL("CREATE TRIGGER IF NOT EXISTS words_note_revision AFTER UPDATE OF meaning ON words BEGIN UPDATE words SET note_revision=note_revision+1 WHERE id=NEW.id; END")
        }
    }
}
