//! 学习者模型数据库存取器：负责 learner_model 与 user_overrides 表的增删改查及投影重构。
package org.qingjian.speech.learning.learner

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.qingjian.speech.learning.archive.NormalizedEvent

class LearnerModelStore(private val db: SQLiteDatabase) {

    fun get(wordId: Long, dimension: String): LearnerModelEntry? {
        return db.rawQuery(
            "SELECT word_id, dimension, state, attempts, correct, assisted, independent_attempts, " +
                "independent_correct, streak, last_success_day, last_at, next_due, score, origin, rule_version " +
                "FROM learner_model WHERE word_id=? AND dimension=?",
            arrayOf(wordId.toString(), dimension)
        ).use { c ->
            if (c.moveToFirst()) {
                LearnerModelEntry(
                    wordId = c.getLong(0),
                    dimension = c.getString(1),
                    state = c.getString(2),
                    attempts = c.getInt(3),
                    correct = c.getInt(4),
                    assisted = c.getInt(5),
                    independentAttempts = c.getInt(6),
                    independentCorrect = c.getInt(7),
                    streak = c.getInt(8),
                    lastSuccessDay = if (c.isNull(9)) null else c.getString(9),
                    lastAt = if (c.isNull(10)) null else c.getLong(10),
                    nextDue = if (c.isNull(11)) null else c.getLong(11),
                    score = if (c.isNull(12)) null else c.getDouble(12),
                    origin = c.getString(13),
                    ruleVersion = c.getInt(14)
                )
            } else null
        }
    }

    fun forWord(wordId: Long): List<LearnerModelEntry> {
        val list = mutableListOf<LearnerModelEntry>()
        db.rawQuery(
            "SELECT word_id, dimension, state, attempts, correct, assisted, independent_attempts, " +
                "independent_correct, streak, last_success_day, last_at, next_due, score, origin, rule_version " +
                "FROM learner_model WHERE word_id=?",
            arrayOf(wordId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list += LearnerModelEntry(
                    wordId = c.getLong(0),
                    dimension = c.getString(1),
                    state = c.getString(2),
                    attempts = c.getInt(3),
                    correct = c.getInt(4),
                    assisted = c.getInt(5),
                    independentAttempts = c.getInt(6),
                    independentCorrect = c.getInt(7),
                    streak = c.getInt(8),
                    lastSuccessDay = if (c.isNull(9)) null else c.getString(9),
                    lastAt = if (c.isNull(10)) null else c.getLong(10),
                    nextDue = if (c.isNull(11)) null else c.getLong(11),
                    score = if (c.isNull(12)) null else c.getDouble(12),
                    origin = c.getString(13),
                    ruleVersion = c.getInt(14)
                )
            }
        }
        return list
    }

    fun save(entry: LearnerModelEntry) {
        val values = ContentValues().apply {
            put("word_id", entry.wordId)
            put("dimension", entry.dimension)
            put("state", entry.state)
            put("attempts", entry.attempts)
            put("correct", entry.correct)
            put("assisted", entry.assisted)
            put("independent_attempts", entry.independentAttempts)
            put("independent_correct", entry.independentCorrect)
            put("streak", entry.streak)
            put("last_success_day", entry.lastSuccessDay)
            put("last_at", entry.lastAt)
            put("next_due", entry.nextDue)
            put("score", entry.score)
            put("origin", entry.origin)
            put("rule_version", entry.ruleVersion)
        }
        db.insertWithOnConflict("learner_model", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveBatch(entries: Collection<LearnerModelEntry>) {
        db.beginTransaction()
        try {
            for (entry in entries) {
                save(entry)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 读取所有激活状态的手动覆盖项 */
    fun activeOverrides(): Map<Pair<Long, String>, UserOverride> {
        val map = mutableMapOf<Pair<Long, String>, UserOverride>()
        db.rawQuery(
            "SELECT word_id, dimension, override_type, created_at, valid_until, active, release_reason, released_at " +
                "FROM user_overrides WHERE active=1",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val o = UserOverride(
                    wordId = c.getLong(0),
                    dimension = c.getString(1),
                    overrideType = c.getString(2),
                    createdAt = c.getLong(3),
                    validUntil = c.getLong(4),
                    active = c.getInt(5) == 1,
                    releaseReason = if (c.isNull(6)) null else c.getString(6),
                    releasedAt = if (c.isNull(7)) null else c.getLong(7)
                )
                map[o.wordId to o.dimension] = o
            }
        }
        return map
    }

    fun saveOverride(override: UserOverride) {
        val values = ContentValues().apply {
            put("word_id", override.wordId)
            put("dimension", override.dimension)
            put("override_type", override.overrideType)
            put("created_at", override.createdAt)
            put("valid_until", override.validUntil)
            put("active", if (override.active) 1 else 0)
            put("release_reason", override.releaseReason)
            put("released_at", override.releasedAt)
        }
        db.insertWithOnConflict("user_overrides", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun releaseOverride(wordId: Long, dimension: String, reason: String, now: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("active", 0)
            put("release_reason", reason)
            put("released_at", now)
        }
        db.update("user_overrides", values, "word_id=? AND dimension=? AND active=1", arrayOf(wordId.toString(), dimension))
    }

    /**
     * 重建全量模型投影：清空 learner_model 并由规范化事件流全量确定性重构。
     */
    fun rebuild(events: List<NormalizedEvent>) {
        val overrides = activeOverrides()
        val projected = LearnerModelEngine.project(events, emptyMap(), overrides)
        db.beginTransaction()
        try {
            db.delete("learner_model", null, null)
            for (entry in projected.values) {
                save(entry)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
