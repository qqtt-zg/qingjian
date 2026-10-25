//! 阶段 0B 标准测试夹具：构建覆盖所有题型与边缘场景的原始事件。
package org.qingjian.speech.learning.learner

import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.archive.EventReader.Companion.RawEventRecord

object EventFixtures {

    fun spellingEvent(
        key: String = "sp-1",
        wordId: Long = 100L,
        english: String = "candidate",
        correct: Boolean = true,
        hint: Int = 0,
        time: Long = 1792836000000L
    ): RawEventRecord {
        val data = JSONObject()
            .put("answer", if (correct) english else "candadite")
            .put("correct", correct)
            .put("hint", hint)
            .put("assisted", hint > 0)
        return RawEventRecord(key, wordId, english, "拼写", data, time)
    }

    fun choiceEvent(
        key: String = "quiz-1",
        wordId: Long = 100L,
        english: String = "candidate",
        correct: Boolean = true,
        time: Long = 1792836000000L,
        assisted: Boolean? = false
    ): RawEventRecord {
        val data = JSONObject()
            .put("answer", "候选人")
            .put("correct", correct)
        if (assisted != null) data.put("assisted", assisted)
        return RawEventRecord(key, wordId, english, "CHOICE", data, time)
    }

    fun englishChoiceEvent(
        key: String = "quiz-2",
        wordId: Long = 100L,
        english: String = "candidate",
        correct: Boolean = true,
        time: Long = 1792836000000L
    ): RawEventRecord {
        val data = JSONObject()
            .put("answer", english)
            .put("correct", correct)
            .put("assisted", false)
        return RawEventRecord(key, wordId, english, "ENGLISH_CHOICE", data, time)
    }

    fun clozeEvent(
        key: String = "quiz-3",
        wordId: Long = 100L,
        english: String = "candidate",
        correct: Boolean = true,
        time: Long = 1792836000000L
    ): RawEventRecord {
        val data = JSONObject()
            .put("answer", english)
            .put("correct", correct)
            .put("assisted", false)
        return RawEventRecord(key, wordId, english, "CLOZE", data, time)
    }

    fun examEvent(
        key: String = "exam-1",
        wordId: Long = 100L,
        english: String = "candidate",
        mode: String = "看义拼写",
        correct: Boolean = true,
        time: Long = 1792836000000L
    ): RawEventRecord {
        val data = JSONObject()
            .put("answer", if (correct) english else "wrong")
            .put("correct", correct)
            .put("mode", mode)
            .put("assisted", false)
        return RawEventRecord(key, wordId, english, "独立考试", data, time)
    }

    fun oralWordEvent(
        key: String = "oral-1",
        wordId: Long = 100L,
        english: String = "candidate",
        score: Double = 85.0,
        state: String = "succeeded",
        time: Long = 1792836000000L
    ): RawEventRecord {
        val result = JSONObject()
            .put("total", score)
            .put("words", JSONArray().put(JSONObject().put("text", english).put("score", score)))
        val data = JSONObject()
            .put("state", state)
            .put("mode", "word")
            .put("result", result)
        return RawEventRecord(key, wordId, english, "发音评测", data, time)
    }

    fun oralSentenceEvent(
        key: String = "oral-2",
        wordId: Long = 100L,
        english: String = "candidate",
        sentenceTotal: Double = 95.0,
        targetWordScore: Double? = 65.0,
        state: String = "succeeded",
        time: Long = 1792836000000L
    ): RawEventRecord {
        val words = JSONArray()
        words.put(JSONObject().put("text", "The").put("score", 98.0))
        words.put(JSONObject().put("text", english).put("score", targetWordScore ?: JSONObject.NULL))
        words.put(JSONObject().put("text", "won").put("score", 96.0))
        val result = JSONObject()
            .put("total", sentenceTotal)
            .put("mode", "sentence")
            .put("words", words)
        val data = JSONObject()
            .put("state", state)
            .put("mode", "sentence")
            .put("result", result)
        return RawEventRecord(key, wordId, english, "发音评测", data, time)
    }

    fun exposureEvent(
        key: String = "exp-1",
        wordId: Long = 100L,
        english: String = "candidate",
        time: Long = 1792836000000L
    ): RawEventRecord {
        return RawEventRecord(key, wordId, english, "EXPOSURE", JSONObject(), time)
    }

    fun selfReviewEvent(
        key: String = "review-1",
        wordId: Long = 100L,
        english: String = "candidate",
        remembered: Boolean = true,
        time: Long = 1792836000000L
    ): RawEventRecord {
        return RawEventRecord(key, wordId, english, "SELF_REVIEW", JSONObject().put("remembered", remembered), time)
    }
}
