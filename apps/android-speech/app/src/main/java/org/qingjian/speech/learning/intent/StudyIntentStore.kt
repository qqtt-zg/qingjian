//! 待练意图本地持久化仓库；负责意图去重、查询未过期待练项、状态流转与过期物理淘汰。
package org.qingjian.speech.learning.intent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.qingjian.speech.learning.LearningStore

class StudyIntentStore(context: Context) {
    private val helper = LearningStore.get(context)
    private val db get() = helper.writableDatabase

    /**
     * 保存一条待练意图。使用 intent_key 唯一约束进行 CONFLICT_IGNORE 幂等写入。
     * 若 targetWordId 为 0，会尝试从 words 表根据 english 挂接已存在词汇。
     */
    fun saveIntent(
        english: String,
        dimension: String = "",
        source: String = "desktop",
        intentKey: String,
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MILLIS,
        priority: Int = 0,
        targetWordId: Long = 0L
    ): Long {
        val word = english.trim()
        if (word.isEmpty() || word.length > 100) return -1L

        var wordId = targetWordId
        if (wordId <= 0L) {
            val key = word.lowercase(java.util.Locale.ROOT)
            wordId = db.rawQuery("SELECT id FROM words WHERE lookup=?", arrayOf(key)).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }

        val values = ContentValues().apply {
            put("intent_key", intentKey)
            put("word_id", wordId)
            put("english", word)
            put("target_dimension", dimension)
            put("source", source)
            put("status", "pending")
            put("created_at", createdAt)
            put("expires_at", expiresAt)
            put("scheduled_plan_id", 0L)
            put("consumed_at", 0L)
            put("priority", priority)
        }
        return db.insertWithOnConflict("study_intents", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * 读取所有处于 pending 状态且未过期的待练意图。
     */
    fun getActiveIntents(nowMillis: Long = System.currentTimeMillis()): List<StudyIntent> {
        val list = mutableListOf<StudyIntent>()
        db.rawQuery(
            "SELECT id, intent_key, word_id, english, target_dimension, source, status, created_at, expires_at, scheduled_plan_id, consumed_at, priority FROM study_intents WHERE status = 'pending' AND expires_at > ? ORDER BY priority DESC, id ASC",
            arrayOf(nowMillis.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list += StudyIntent(
                    id = c.getLong(0),
                    intentKey = c.getString(1),
                    wordId = c.getLong(2),
                    english = c.getString(3),
                    targetDimension = c.getString(4),
                    source = c.getString(5),
                    status = c.getString(6),
                    createdAt = c.getLong(7),
                    expiresAt = c.getLong(8),
                    scheduledPlanId = c.getLong(9),
                    consumedAt = c.getLong(10),
                    priority = c.getInt(11)
                )
            }
        }
        return list
    }

    /**
     * 标记意图已编入今日计划。
     */
    fun markScheduled(intentIds: List<Long>, planId: Long) {
        if (intentIds.isEmpty()) return
        val placeholders = intentIds.joinToString(",") { "?" }
        val args = (listOf(planId.toString()) + intentIds.map { it.toString() }).toTypedArray()
        db.execSQL("UPDATE study_intents SET status = 'scheduled', scheduled_plan_id = ? WHERE id IN ($placeholders) AND status = 'pending'", args)
    }

    /**
     * 标记意图已被完成消费（按 wordId）。
     */
    fun markConsumed(wordId: Long, nowMillis: Long = System.currentTimeMillis()) {
        db.execSQL(
            "UPDATE study_intents SET status = 'consumed', consumed_at = ? WHERE word_id = ? AND status IN ('pending', 'scheduled')",
            arrayOf<Any>(nowMillis, wordId)
        )
    }

    /**
     * 淘汰过期意图（超过 30 天未消费）。
     */
    fun expireOldIntents(nowMillis: Long = System.currentTimeMillis()): Int {
        val values = ContentValues().apply { put("status", "expired") }
        return db.update("study_intents", values, "status = 'pending' AND expires_at <= ?", arrayOf(nowMillis.toString()))
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30L * 86400000L
    }
}
