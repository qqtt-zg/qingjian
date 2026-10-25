//! 规范化事件读取与聚合器：将分散在多张表中的历史与实时事件统一转换为规范化事件契约。
package org.qingjian.speech.learning.archive

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject
import org.qingjian.speech.learning.quiz.Question

class EventReader(private val db: SQLiteDatabase) {

    /** 读取所有规范化事件，已去重并按发生时间全序排列 */
    fun readNormalizedEvents(zoneId: ZoneId = ZoneId.systemDefault()): List<NormalizedEvent> {
        val rawList = mutableListOf<RawEventRecord>()

        // 1. learning_events 表
        db.rawQuery(
            "SELECT event_key, word_id, english, kind, payload, created FROM learning_events",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val payload = runCatching { JSONObject(c.getString(4)) }.getOrDefault(JSONObject())
                rawList += RawEventRecord(
                    key = c.getString(0),
                    wordId = c.getLong(1),
                    english = c.getString(2),
                    kind = c.getString(3),
                    data = payload,
                    created = c.getLong(5)
                )
            }
        }

        // 2. quiz_attempts 表（独立测验）
        // 检查其题目是否来自 study_questions 的 EXAM 阶段且标记了 assisted
        val assistedKeys = db.rawQuery(
            "SELECT snapshot FROM study_questions WHERE phase='EXAM' AND assisted=1",
            null
        ).use { c ->
            buildSet {
                while (c.moveToNext()) {
                    val snap = runCatching { JSONObject(c.getString(0)) }.getOrNull()
                    if (snap != null && snap.has("key")) {
                        add("quiz:" + snap.getString("key"))
                    }
                }
            }
        }

        // 检查哪些 quiz 是在 EXAM 阶段产出的
        val examKeys = db.rawQuery(
            "SELECT snapshot FROM study_questions WHERE phase='EXAM'",
            null
        ).use { c ->
            buildSet {
                while (c.moveToNext()) {
                    val snap = runCatching { JSONObject(c.getString(0)) }.getOrNull()
                    if (snap != null && snap.has("key")) {
                        add("quiz:" + snap.getString("key"))
                    }
                }
            }
        }

        db.rawQuery(
            "SELECT question_key, word_id, english, kind, answer, correct, created FROM quiz_attempts",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val qKey = "quiz:" + c.getString(0)
                val isExam = qKey in examKeys
                val isAssisted = if (isExam) (qKey in assistedKeys) else null // 旧卡片非考试没有防作弊证据，记为 unknown
                val data = JSONObject()
                    .put("answer", c.getString(4))
                    .put("correct", c.getInt(5) == 1)
                if (isAssisted != null) {
                    data.put("assisted", isAssisted)
                }
                rawList += RawEventRecord(
                    key = qKey,
                    wordId = c.getLong(1),
                    english = c.getString(2),
                    kind = c.getString(3),
                    data = data,
                    created = c.getLong(6)
                )
            }
        }

        // 3. oral_assessments 表（补充模式、录音信息）
        val existingKeys = rawList.map { it.key }.toSet()
        db.rawQuery(
            "SELECT a.id, a.word_id, a.word_snapshot, a.state, a.result, a.created, a.mode " +
                "FROM oral_assessments a",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val key = "oral:" + c.getString(0)
                if (key !in existingKeys && ("assessment:" + c.getString(0)) !in existingKeys) {
                    val data = JSONObject()
                        .put("state", c.getString(3))
                        .put("result", c.getString(4))
                        .put("mode", c.getString(6))
                    rawList += RawEventRecord(
                        key = key,
                        wordId = c.getLong(1),
                        english = c.getString(2),
                        kind = "发音评测",
                        data = data,
                        created = c.getLong(5)
                    )
                }
            }
        }

        // 4. study_questions 表中的 PRACTICE 阶段
        db.rawQuery(
            "SELECT q.snapshot, q.answer, q.assisted, p.updated, q.plan_id, q.listening " +
                "FROM study_questions q JOIN study_plans p ON p.id=q.plan_id " +
                "WHERE q.phase='PRACTICE' AND q.answer!=''",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val snap = runCatching { JSONObject(c.getString(0)) }.getOrNull() ?: continue
                val qKey = snap.optString("key")
                val planId = c.getLong(4)
                val key = "daily:$planId:$qKey"
                if (key !in existingKeys) {
                    val wordId = snap.optLong("word", snap.optLong("word_id", 0L))
                    val english = snap.optString("english")
                    val qKind = snap.optString("kind")
                    val answer = c.getString(1)
                    val assisted = c.getInt(2) == 1
                    val listening = c.getInt(5) == 1
                    val options = snap.optJSONArray("options")
                    val isCorrect = if (qKind == "CHOICE") {
                        answer == snap.optString("meaning")
                    } else {
                        Question.normalize(answer) == Question.normalize(english)
                    }

                    val data = JSONObject()
                        .put("answer", answer)
                        .put("correct", isCorrect)
                        .put("assisted", assisted)
                        .put("listening", listening)
                        .put("question_kind", qKind)
                        .put("time_note", "旧每日练习按计划更新时间归档")

                    rawList += RawEventRecord(
                        key = key,
                        wordId = wordId,
                        english = english,
                        kind = "每日练习",
                        data = data,
                        created = c.getLong(3),
                        planId = planId
                    )
                }
            }
        }

        return normalizeRawEvents(rawList, zoneId)
    }

    /** 读取单个单词的所有规范化事件 */
    fun readWordEvents(wordId: Long, zoneId: ZoneId = ZoneId.systemDefault()): List<NormalizedEvent> {
        return readNormalizedEvents(zoneId).filter { it.wordId == wordId }
    }

    companion object {
        data class RawEventRecord(
            val key: String,
            val wordId: Long,
            val english: String,
            val kind: String,
            val data: JSONObject,
            val created: Long,
            val planId: Long? = null
        )

        /**
         * 纯函数：将原始记录列表归一化并执行：
         * 1. 过滤无效/缺失字段；
         * 2. (eventId + ":" + dimension) 唯一去重；
         * 3. (occurredAt ASC, eventId ASC, dimension.key ASC) 严格全序排序。
         */
        fun normalizeRawEvents(
            rawList: List<RawEventRecord>,
            zoneId: ZoneId = ZoneId.systemDefault()
        ): List<NormalizedEvent> {
            val events = mutableListOf<NormalizedEvent>()
            val seenKeys = mutableSetOf<String>()

            for (raw in rawList) {
                val normalized = parseRecord(raw, zoneId) ?: continue
                val dedupeKey = "${normalized.eventId}:${normalized.dimension.key}"
                if (dedupeKey in seenKeys) continue
                seenKeys.add(dedupeKey)
                events.add(normalized)
            }

            return events.sortedWith(
                compareBy<NormalizedEvent> { it.occurredAt }
                    .thenBy { it.eventId }
                    .thenBy { it.dimension.key }
            )
        }

        fun parseRecord(raw: RawEventRecord, zoneId: ZoneId = ZoneId.systemDefault()): NormalizedEvent? {
            if (raw.wordId <= 0L || raw.english.isBlank()) return null
            val time = raw.created
            val day = Instant.ofEpochMilli(time).atZone(zoneId).toLocalDate().toString()
            val zone = zoneId.id
            val data = raw.data

            when (raw.kind) {
                // 1. 盲写事件
                "拼写" -> {
                    val correct = if (data.has("correct")) data.getBoolean("correct") else null
                    val hint = data.optInt("hint", 0)
                    val assisted = if (data.has("assisted")) data.getBoolean("assisted") else (hint > 0)
                    return NormalizedEvent(
                        eventId = if (raw.key.startsWith("spelling:")) raw.key else "spelling:${raw.key}",
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = EventDimension.SPELLING,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.GRADED,
                        correct = correct,
                        assisted = assisted,
                        rawPayload = data.toString()
                    )
                }

                // 2. 独立考试
                "独立考试" -> {
                    val mode = data.optString("mode")
                    val dim = when (mode) {
                        "听写" -> EventDimension.LISTENING
                        "看义拼写", "语境填词" -> EventDimension.SPELLING
                        else -> EventDimension.SPELLING
                    }
                    val correct = if (data.has("correct")) data.getBoolean("correct") else null
                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = dim,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.GRADED,
                        correct = correct,
                        assisted = false, // 独立考试为独立作答
                        rawPayload = data.toString()
                    )
                }

                // 3. 发音评测
                "发音评测" -> {
                    val state = data.optString("state")
                    if (state != "succeeded") {
                        // 未成功评测不进入有效能力 attempt
                        return NormalizedEvent(
                            eventId = raw.key,
                            wordId = raw.wordId,
                            english = raw.english,
                            dimension = EventDimension.PRONUNCIATION,
                            occurredAt = time,
                            day = day,
                            zone = zone,
                            timePrecision = TimePrecision.EXACT,
                            outcome = EventOutcome.ASSESSMENT,
                            correct = null,
                            assisted = false,
                            score = null,
                            provider = "xfyun",
                            rawPayload = data.toString()
                        )
                    }

                    val rawResult = data.opt("result")
                    val resultJson = when (rawResult) {
                        is JSONObject -> rawResult
                        is String -> runCatching { JSONObject(rawResult) }.getOrNull()
                        else -> null
                    } ?: return null

                    val mode = data.optString("mode", resultJson.optString("mode", "word"))
                    val totalScore = if (resultJson.has("total") && !resultJson.isNull("total")) {
                        resultJson.optDouble("total")
                    } else null

                    // 句子总分不能直接覆盖单词发音状态
                    val wordScore: Double? = if (mode == "sentence") {
                        extractWordScoreFromSentence(resultJson, raw.english)
                    } else {
                        // 词模式：优先取匹配单词分，其次取总分
                        val wordMatch = extractWordScoreFromSentence(resultJson, raw.english)
                        wordMatch ?: totalScore
                    }

                    val correct = if (wordScore != null) (wordScore >= 80.0) else null

                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = EventDimension.PRONUNCIATION,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.ASSESSMENT,
                        correct = correct,
                        assisted = false,
                        score = wordScore,
                        provider = "xfyun",
                        referenceMode = mode,
                        rawPayload = data.toString()
                    )
                }

                // 4. 每日练习
                "每日练习" -> {
                    val listening = data.optBoolean("listening", false)
                    val qKind = data.optString("question_kind")
                    val dim = if (listening) {
                        EventDimension.LISTENING
                    } else if (qKind == "SPELLING") {
                        EventDimension.SPELLING
                    } else {
                        EventDimension.MEANING
                    }

                    val correct = if (data.has("correct")) data.getBoolean("correct") else null
                    val assisted = if (data.has("assisted")) data.getBoolean("assisted") else false

                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = dim,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.DAY_INFERRED,
                        outcome = EventOutcome.GRADED,
                        correct = correct,
                        assisted = assisted,
                        planId = raw.planId,
                        rawPayload = data.toString()
                    )
                }

                // 5. quiz_attempts 的各类 Kind
                "CHOICE", "ENGLISH_CHOICE", "CLOZE" -> {
                    // 普通词义选择、英文选词、搭配选择均归为 meaning（严禁把选出英文算作独立拼写）
                    val correct = if (data.has("correct")) data.getBoolean("correct") else null
                    val assisted = if (data.has("assisted")) data.getBoolean("assisted") else null
                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = EventDimension.MEANING,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.GRADED,
                        correct = correct,
                        assisted = assisted,
                        rawPayload = data.toString()
                    )
                }

                "SPELLING" -> {
                    val correct = if (data.has("correct")) data.getBoolean("correct") else null
                    val assisted = if (data.has("assisted")) data.getBoolean("assisted") else null
                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = EventDimension.SPELLING,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.GRADED,
                        correct = correct,
                        assisted = assisted,
                        rawPayload = data.toString()
                    )
                }

                // 曝光与自评
                "EXPOSURE" -> {
                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = EventDimension.MEANING,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.EXPOSURE,
                        correct = null,
                        assisted = null,
                        rawPayload = data.toString()
                    )
                }

                "SELF_REVIEW" -> {
                    val remembered = data.optBoolean("remembered", false)
                    return NormalizedEvent(
                        eventId = raw.key,
                        wordId = raw.wordId,
                        english = raw.english,
                        dimension = EventDimension.MEANING,
                        occurredAt = time,
                        day = day,
                        zone = zone,
                        timePrecision = TimePrecision.EXACT,
                        outcome = EventOutcome.SELF_REVIEW,
                        correct = remembered,
                        assisted = false,
                        rawPayload = data.toString()
                    )
                }

                else -> return null
            }
        }

        private fun extractWordScoreFromSentence(result: JSONObject, english: String): Double? {
            val words = result.optJSONArray("words") ?: return null
            val target = Question.normalize(english)
            for (i in 0 until words.length()) {
                val item = words.optJSONObject(i) ?: continue
                val text = item.optString("text")
                if (Question.normalize(text) == target) {
                    if (item.has("score") && !item.isNull("score")) {
                        val s = item.optDouble("score")
                        if (s.isFinite() && s in 0.0..100.0) return s
                    }
                }
            }
            return null
        }
    }
}
