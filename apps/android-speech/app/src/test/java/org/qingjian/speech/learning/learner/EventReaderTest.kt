//! 规范化事件契约的单元测试：验证题型映射、词级与句子级发音隔离、去重与全序排序。
package org.qingjian.speech.learning.learner

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qingjian.speech.learning.archive.EventDimension
import org.qingjian.speech.learning.archive.EventOutcome
import org.qingjian.speech.learning.archive.EventReader

class EventReaderTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun testBlindSpellingMapsToSpellingAndPreservesAssisted() {
        // 独立盲写
        val rawIndependent = EventFixtures.spellingEvent(key = "sp-indep", hint = 0, correct = true)
        val eventIndep = checkNotNull(EventReader.parseRecord(rawIndependent, zone))
        assertEquals(EventDimension.SPELLING, eventIndep.dimension)
        assertEquals(EventOutcome.GRADED, eventIndep.outcome)
        assertEquals(true, eventIndep.correct)
        assertEquals(false, eventIndep.assisted)

        // 提示辅助后盲写
        val rawAssisted = EventFixtures.spellingEvent(key = "sp-assist", hint = 2, correct = true)
        val eventAssist = checkNotNull(EventReader.parseRecord(rawAssisted, zone))
        assertEquals(EventDimension.SPELLING, eventAssist.dimension)
        assertEquals(true, eventAssist.assisted)
    }

    @Test
    fun testChoicesMapToMeaningAndCannotBeSpelling() {
        val raw1 = EventFixtures.choiceEvent(key = "q1")
        val ev1 = checkNotNull(EventReader.parseRecord(raw1, zone))
        assertEquals(EventDimension.MEANING, ev1.dimension)

        // 英文选词必须映射为 meaning，严禁算作拼写
        val raw2 = EventFixtures.englishChoiceEvent(key = "q2")
        val ev2 = checkNotNull(EventReader.parseRecord(raw2, zone))
        assertEquals(EventDimension.MEANING, ev2.dimension)

        // 搭配填空归为 meaning
        val raw3 = EventFixtures.clozeEvent(key = "q3")
        val ev3 = checkNotNull(EventReader.parseRecord(raw3, zone))
        assertEquals(EventDimension.MEANING, ev3.dimension)
    }

    @Test
    fun testIndependentExamModes() {
        val dictation = EventFixtures.examEvent(key = "ex-1", mode = "听写")
        val evDict = checkNotNull(EventReader.parseRecord(dictation, zone))
        assertEquals(EventDimension.LISTENING, evDict.dimension)
        assertEquals(false, evDict.assisted)

        val spelling = EventFixtures.examEvent(key = "ex-2", mode = "看义拼写")
        val evSpell = checkNotNull(EventReader.parseRecord(spelling, zone))
        assertEquals(EventDimension.SPELLING, evSpell.dimension)

        val cloze = EventFixtures.examEvent(key = "ex-3", mode = "语境填词")
        val evCloze = checkNotNull(EventReader.parseRecord(cloze, zone))
        assertEquals(EventDimension.SPELLING, evCloze.dimension)
    }

    @Test
    fun testSentenceAssessmentSeparatesWordScore() {
        // 句子总分 95，但目标词得分 65
        val raw = EventFixtures.oralSentenceEvent(
            key = "oral-sent-1",
            sentenceTotal = 95.0,
            targetWordScore = 65.0
        )
        val ev = checkNotNull(EventReader.parseRecord(raw, zone))
        assertEquals(EventDimension.PRONUNCIATION, ev.dimension)
        assertEquals(EventOutcome.ASSESSMENT, ev.outcome)
        // 核心约束：句子总分不直接覆盖单词发音状态，采纳词级 65.0
        assertEquals(65.0, ev.score!!, 0.001)
        assertFalse("低于 80 分不算正确", ev.correct!!)
    }

    @Test
    fun testFailedAssessmentYieldsNullScore() {
        val failed = EventFixtures.oralWordEvent(
            key = "oral-fail-1",
            state = "failed"
        )
        val ev = checkNotNull(EventReader.parseRecord(failed, zone))
        assertNull(ev.score)
        assertNull(ev.correct)
    }

    @Test
    fun testDeduplicationAndStableSorting() {
        val timeBase = 1792836000000L
        val list = listOf(
            EventFixtures.spellingEvent(key = "evt-2", time = timeBase + 2000L),
            EventFixtures.spellingEvent(key = "evt-1", time = timeBase + 1000L),
            // 重复 ID 与维度
            EventFixtures.spellingEvent(key = "evt-1", time = timeBase + 1000L),
            EventFixtures.choiceEvent(key = "evt-3", time = timeBase)
        )

        val normalized = EventReader.normalizeRawEvents(list, zone)
        assertEquals(3, normalized.size)
        // 升序排列
        assertEquals("evt-3", normalized[0].eventId)
        assertEquals("spelling:evt-1", normalized[1].eventId)
        assertEquals("spelling:evt-2", normalized[2].eventId)
    }
}
