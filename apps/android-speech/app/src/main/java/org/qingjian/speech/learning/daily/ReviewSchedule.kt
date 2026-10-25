//! 分项复习日期；独立小测与自评分别记录，浏览或模型建议不增加正确次数。
package org.qingjian.speech.learning.daily

import android.database.sqlite.SQLiteDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReviewSchedule {
    fun record(db: SQLiteDatabase, wordId: Long, skill: String, correct: Boolean, now: Long = System.currentTimeMillis()) {
        db.delete("study_reviews", "word_id=? AND skill='EXPOSURE'", arrayOf(wordId.toString()))
        val previous = db.rawQuery("SELECT streak,last_at FROM study_reviews WHERE word_id=? AND skill=?", arrayOf(wordId.toString(), skill)).use {
            if(it.moveToFirst()) it.getInt(0) to it.getLong(1) else 0 to 0L
        }
        val (next, _) = ReviewScheduleCore.calculateStreak(previous.first, previous.second, now, correct)
        val nextDue = ReviewScheduleCore.calculateNextDue(next, now)
        db.execSQL("INSERT OR REPLACE INTO study_reviews(word_id,skill,streak,next_due,last_at) VALUES(?,?,?,?,?)",
            arrayOf<Any>(wordId, skill, next, nextDue, now))
    }

    fun exposure(db: SQLiteDatabase, wordId: Long, now: Long = System.currentTimeMillis()) {
        db.execSQL("INSERT OR IGNORE INTO study_reviews(word_id,skill,streak,next_due,last_at) SELECT ?,'EXPOSURE',0,?,? WHERE NOT EXISTS(SELECT 1 FROM study_reviews WHERE word_id=?)", arrayOf<Any>(wordId, now + 86400000L, now, wordId))
    }
}
