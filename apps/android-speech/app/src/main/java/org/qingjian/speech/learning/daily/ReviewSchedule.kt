//! 分项复习日期；独立小测与自评分别记录，浏览或模型建议不增加正确次数。
package org.qingjian.speech.learning.daily

import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReviewSchedule {
    fun record(db: SQLiteDatabase, wordId: Long, skill: String, correct: Boolean, now: Long = System.currentTimeMillis()) {
        db.delete("study_reviews", "word_id=? AND skill='EXPOSURE'", arrayOf(wordId.toString()))
        val previous = db.rawQuery("SELECT streak,last_at FROM study_reviews WHERE word_id=? AND skill=?", arrayOf(wordId.toString(), skill)).use { if(it.moveToFirst()) it.getInt(0) to it.getLong(1) else 0 to 0L }
        val format=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT)
        val sameDay=format.format(Date(previous.second))==format.format(Date(now))
        val next = if(!correct)0 else if(sameDay&&previous.first>0)previous.first else (previous.first+1).coerceAtMost(5)
        val days = listOf(1, 1, 3, 7, 14, 30)[next]
        db.execSQL("INSERT OR REPLACE INTO study_reviews(word_id,skill,streak,next_due,last_at) VALUES(?,?,?,?,?)",
            arrayOf<Any>(wordId, skill, next, now + days * 86400000L, now))
    }

    fun exposure(db: SQLiteDatabase, wordId: Long, now: Long = System.currentTimeMillis()) {
        db.execSQL("INSERT OR IGNORE INTO study_reviews(word_id,skill,streak,next_due,last_at) SELECT ?,'EXPOSURE',0,?,? WHERE NOT EXISTS(SELECT 1 FROM study_reviews WHERE word_id=?)", arrayOf<Any>(wordId, now + 86400000L, now, wordId))
    }
}
