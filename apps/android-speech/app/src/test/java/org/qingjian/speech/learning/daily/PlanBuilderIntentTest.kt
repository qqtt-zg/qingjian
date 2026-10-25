//! 待练意图排程提权单元测试：验证意图词优先排入 Due/Weak/Fresh 池、豁免连续跳过暂停与硬预算守护。
package org.qingjian.speech.learning.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.qingjian.speech.learning.VocabularyEntry
import org.qingjian.speech.learning.intent.StudyIntent
import org.qingjian.speech.learning.learner.LearnerModelEntry

class PlanBuilderIntentTest {

    private fun sampleIntent(wordId: Long, english: String, dimension: String = "") = StudyIntent(
        id = wordId,
        intentKey = "desktop:$wordId:$english",
        wordId = wordId,
        english = english,
        targetDimension = dimension,
        source = "desktop",
        status = "pending",
        createdAt = 1000L,
        expiresAt = 1000L + 30 * 86400000L
    )

    @Test
    fun testIntentPrioritizedInDuePool() {
        val words = listOf(
            VocabularyEntry(1L, "regular", "常规词", 1, 1, 0, 1000L),
            VocabularyEntry(2L, "intended", "意图词", 1, 1, 0, 1000L)
        )
        val entries = listOf(
            LearnerModelEntry(1L, "spelling", "learning", 2, 0, 0, 0, 0, 0, null, 1000L, 500L, 1.0, "test", 1),
            LearnerModelEntry(2L, "spelling", "learning", 2, 0, 0, 0, 0, 0, null, 1000L, 500L, 1.0, "test", 1)
        )
        val intents = listOf(sampleIntent(2L, "intended", "spelling"))

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = entries,
            activeIntents = intents,
            nowMillis = 2000L
        )

        val limits = PlanConstraints.resolveLimits(usableCredit = 18, preferenceNewWords = 5, isBeginner = false)
        val pack = ContextPack(
            today = "2026-10-25", epoch = 1L, preferencesRevision = 1, desktopRevision = 1L, modelRevision = 1,
            budget = BudgetSnapshot(10, 1.0, "", 18), candidates = pools, totalWords = 2, activeIntents = intents
        )

        val plan = PlanBuilder.build(pack, limits)
        assertEquals(2, plan.items.size)
        // 意图词必须排在首位
        assertEquals("intended", plan.items[0].english)
        assertEquals("due_intent", plan.items[0].reasonCode)
        assertTrue(plan.items[0].reasonText.contains("待练意向"))
    }

    @Test
    fun testIntentPrioritizedInWeakPool() {
        val words = listOf(
            VocabularyEntry(1L, "weak1", "弱1", 1, 1, 0, 1000L),
            VocabularyEntry(2L, "weak2", "弱2", 1, 1, 0, 1000L)
        )
        // 两次错误，非到期
        val entries = listOf(
            LearnerModelEntry(1L, "spelling", "learning", 2, 0, 0, 0, 0, 0, null, 1000L, 5000L, 1.0, "test", 1),
            LearnerModelEntry(2L, "spelling", "learning", 2, 0, 0, 0, 0, 0, null, 1000L, 5000L, 1.0, "test", 1)
        )
        val intents = listOf(sampleIntent(2L, "weak2"))

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = entries,
            activeIntents = intents,
            nowMillis = 2000L
        )

        val limits = PlanConstraints.resolveLimits(usableCredit = 18, preferenceNewWords = 5, isBeginner = false)
        val pack = ContextPack(
            today = "2026-10-25", epoch = 1L, preferencesRevision = 1, desktopRevision = 1L, modelRevision = 1,
            budget = BudgetSnapshot(10, 1.0, "", 18), candidates = pools, totalWords = 2, activeIntents = intents
        )

        val plan = PlanBuilder.build(pack, limits)
        assertEquals(2, plan.items.size)
        assertEquals("weak2", plan.items[0].english)
        assertEquals("weak_intent", plan.items[0].reasonCode)
    }

    @Test
    fun testIntentPrioritizedInFreshPool() {
        val words = listOf(
            VocabularyEntry(1L, "fresh1", "新1", 1, 1, 0, 1000L),
            VocabularyEntry(2L, "fresh2", "新2", 1, 1, 0, 1000L)
        )
        val intents = listOf(sampleIntent(2L, "fresh2"))

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = emptyList(),
            activeIntents = intents,
            nowMillis = 2000L
        )

        val limits = PlanConstraints.resolveLimits(usableCredit = 18, preferenceNewWords = 5, isBeginner = false)
        val pack = ContextPack(
            today = "2026-10-25", epoch = 1L, preferencesRevision = 1, desktopRevision = 1L, modelRevision = 1,
            budget = BudgetSnapshot(10, 1.0, "", 18), candidates = pools, totalWords = 2, activeIntents = intents
        )

        val plan = PlanBuilder.build(pack, limits)
        assertEquals(2, plan.items.size)
        assertEquals("fresh2", plan.items[0].english)
        assertEquals("fresh_intent", plan.items[0].reasonCode)
    }

    @Test
    fun testIntentBypassesThreeDaySkipPause() {
        // 词汇 100L 连续跳过 3 天，但有显式待练意图
        val skipHistories = mapOf(
            100L to SkipHistory(100L, consecutiveSkipDays = 3, lastSkippedDimension = "spelling")
        )
        val words = listOf(
            VocabularyEntry(100L, "skippedThreeTimes", "跳过三次", 1, 1, 0, 1000L)
        )
        val intents = listOf(sampleIntent(100L, "skippedThreeTimes"))

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = emptyList(),
            skipHistories = skipHistories,
            activeIntents = intents,
            nowMillis = 2000L
        )

        // 契约验收：用户显式指定待练意图，可豁免连续 3 天跳过排除
        assertTrue(pools.fresh.any { it.wordId == 100L })
    }

    @Test
    fun testIntentPromotedFromSolidPool() {
        // 稳固词，未到期
        val words = listOf(
            VocabularyEntry(100L, "solidWord", "稳固词", 1, 1, 0, 1000L)
        )
        val entries = listOf(
            LearnerModelEntry(100L, "meaning", "solid", 5, 5, 0, 0, 0, 5, "2026-10-20", 1000L, 500000L, 1.0, "test", 1)
        )
        val intents = listOf(sampleIntent(100L, "solidWord"))

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = entries,
            activeIntents = intents,
            nowMillis = 2000L
        )

        // 进入巩固池待排
        assertTrue(pools.consolidate.any { it.wordId == 100L })

        val limits = PlanConstraints.resolveLimits(usableCredit = 18, preferenceNewWords = 5, isBeginner = false)
        val pack = ContextPack(
            today = "2026-10-25", epoch = 1L, preferencesRevision = 1, desktopRevision = 1L, modelRevision = 1,
            budget = BudgetSnapshot(10, 1.0, "", 18), candidates = pools, totalWords = 1, activeIntents = intents
        )

        val plan = PlanBuilder.build(pack, limits)
        assertEquals(1, plan.items.size)
        assertEquals("solidWord", plan.items[0].english)
        assertEquals("intent_prompted", plan.items[0].reasonCode)
    }

    @Test
    fun testIntentRespectsHardBudgetAndMaxItems() {
        // 构造 15 个待练意图词
        val words = (1..15).map { VocabularyEntry(it.toLong(), "word$it", "词$it", 1, 1, 0, 1000L) }
        val intents = (1..15).map { sampleIntent(it.toLong(), "word$it") }

        val pools = PlanBuilder.assembleCandidatePools(
            words = words,
            learnerEntries = emptyList(),
            activeIntents = intents,
            nowMillis = 2000L
        )

        // 可用限额 10 Credit，maxItems = 10
        val limits = PlanLimits(usableCredit = 10, dueTarget = 0, dueCap = 5, newWordCap = 10, maxItems = 10)
        val pack = ContextPack(
            today = "2026-10-25", epoch = 1L, preferencesRevision = 1, desktopRevision = 1L, modelRevision = 1,
            budget = BudgetSnapshot(10, 1.0, "", 10), candidates = pools, totalWords = 15, activeIntents = intents
        )

        val plan = PlanBuilder.build(pack, limits)
        // 无论意图积压多少，单轮严格不能超过 10 词，总 Credit 严格不超过 usableCredit
        assertTrue(plan.items.size <= 10)
        assertTrue(plan.plannedCredit <= limits.usableCredit)
    }
}
