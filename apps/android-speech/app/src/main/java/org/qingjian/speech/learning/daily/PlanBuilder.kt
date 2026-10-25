//! 纯本机排程算法：无大模型参与，基于不可变快照对到期、薄弱、新词与巩固候选池进行多维合并与确定性排程。
package org.qingjian.speech.learning.daily

import android.content.Context
import org.json.JSONObject
import org.qingjian.speech.learning.VocabularyEntry
import org.qingjian.speech.learning.archive.EventDimension
import org.qingjian.speech.learning.learner.EffectiveDue
import org.qingjian.speech.learning.learner.LearnerModelEntry
import org.qingjian.speech.learning.learner.UserOverride

object PlanBuilder {

    /** 维度在平局时的打破顺序：spelling > listening > meaning > pronunciation */
    private val DIMENSION_TIE_BREAKER = listOf(
        EventDimension.SPELLING.key,
        EventDimension.LISTENING.key,
        EventDimension.MEANING.key,
        EventDimension.PRONUNCIATION.key
    )

    /**
     * 纯函数：根据上下文快照与限制生成确定性计划。
     * 同快照、同配置运行多次，输出逐字段严格一致。
     */
    fun build(pack: ContextPack, limits: PlanLimits): ProposedPlan {
        if (pack.totalWords == 0) {
            return ProposedPlan(
                title = "词库暂无词汇",
                reason = "词库中暂无词汇，请先添加词条或同步电脑生词本。",
                items = emptyList(),
                plannedCredit = 0,
                budgetSnapshotJson = buildBudgetJson(pack.budget, limits)
            )
        }

        // 1. 各候选池内部稳定排序
        val sortedDue = sortDuePool(pack.candidates.due)
        val sortedWeak = sortWeakPool(pack.candidates.weak)
        val sortedFresh = sortFreshPool(pack.candidates.fresh)
        val sortedConsolidate = sortConsolidatePool(pack.candidates.consolidate)

        val picked = mutableListOf<ProposedItem>()
        val pickedWordIds = mutableSetOf<Long>()

        var currentCredit = 0
        var dueCredit = 0
        var freshCount = 0

        // 步骤 1：填充 Due 池达到 dueTarget，且不超过 dueCap
        for (cand in sortedDue) {
            if (picked.size >= limits.maxItems) break
            if (cand.wordId in pickedWordIds) continue

            val item = assembleDueItem(cand, pack.materials[cand.wordId])
            val itemCredit = item.totalCredit
            if (dueCredit + itemCredit <= limits.dueCap && currentCredit + itemCredit <= limits.usableCredit) {
                picked.add(item)
                pickedWordIds.add(cand.wordId)
                dueCredit += itemCredit
                currentCredit += itemCredit
                if (dueCredit >= limits.dueTarget) {
                    break
                }
            }
        }

        // 步骤 2：填充 Weak 池
        for (cand in sortedWeak) {
            if (picked.size >= limits.maxItems) break
            if (cand.wordId in pickedWordIds) continue

            val item = assembleWeakItem(cand, pack.materials[cand.wordId])
            val itemCredit = item.totalCredit
            if (currentCredit + itemCredit <= limits.usableCredit) {
                picked.add(item)
                pickedWordIds.add(cand.wordId)
                currentCredit += itemCredit
            }
        }

        // 步骤 3：填充 Fresh 池（受 newWordCap 约束）
        for (cand in sortedFresh) {
            if (picked.size >= limits.maxItems) break
            if (freshCount >= limits.newWordCap) break
            if (cand.wordId in pickedWordIds) continue

            val item = assembleFreshItem(cand, pack.materials[cand.wordId])
            val itemCredit = item.totalCredit
            if (currentCredit + itemCredit <= limits.usableCredit) {
                picked.add(item)
                pickedWordIds.add(cand.wordId)
                currentCredit += itemCredit
                freshCount++
            }
        }

        // 步骤 4：余量填充（Due cap 内剩余 Due 词与 Consolidate 巩固词）
        if (currentCredit < limits.usableCredit && picked.size < limits.maxItems) {
            for (cand in sortedDue) {
                if (picked.size >= limits.maxItems) break
                if (cand.wordId in pickedWordIds) continue

                val item = assembleDueItem(cand, pack.materials[cand.wordId])
                val itemCredit = item.totalCredit
                if (dueCredit + itemCredit <= limits.dueCap && currentCredit + itemCredit <= limits.usableCredit) {
                    picked.add(item)
                    pickedWordIds.add(cand.wordId)
                    dueCredit += itemCredit
                    currentCredit += itemCredit
                }
            }
        }

        if (currentCredit < limits.usableCredit && picked.size < limits.maxItems) {
            for (cand in sortedConsolidate) {
                if (picked.size >= limits.maxItems) break
                if (cand.wordId in pickedWordIds) continue

                val item = assembleConsolidateItem(cand, pack.materials[cand.wordId])
                val itemCredit = item.totalCredit
                if (currentCredit + itemCredit <= limits.usableCredit) {
                    picked.add(item)
                    pickedWordIds.add(cand.wordId)
                    currentCredit += itemCredit
                }
            }
        }

        // 如果全部 solid 且没有到期和新词任务
        if (picked.isEmpty()) {
            return ProposedPlan(
                title = "今日已全部掌握",
                reason = "当前词库中的词汇均处于稳固状态，今天可以休息或自由复习。",
                items = emptyList(),
                plannedCredit = 0,
                budgetSnapshotJson = buildBudgetJson(pack.budget, limits)
            )
        }

        val dueCount = picked.count { it.reasonCode.startsWith("due") }
        val weakCount = picked.count { it.reasonCode.startsWith("weak") }
        val finalFreshCount = picked.count { it.reasonCode.startsWith("fresh") }
        val consCount = picked.count { it.reasonCode.startsWith("consolidate") }

        val reasonSummary = buildString {
            append("根据你的作答记录与记忆曲线，安排了 ")
            val parts = mutableListOf<String>()
            if (dueCount > 0) parts.add("${dueCount} 个到期复习")
            if (weakCount > 0) parts.add("${weakCount} 个薄弱强化")
            if (finalFreshCount > 0) parts.add("${finalFreshCount} 个新词精进")
            if (consCount > 0) parts.add("${consCount} 个巩固保温")
            append(parts.joinToString("、"))
            append("。")
        }

        return ProposedPlan(
            title = "今日自主学习计划",
            reason = reasonSummary,
            items = picked,
            plannedCredit = currentCredit,
            budgetSnapshotJson = buildBudgetJson(pack.budget, limits)
        )
    }

    private fun sortDuePool(due: List<CandidateWord>): List<CandidateWord> {
        return due.sortedWith(
            compareByDescending<CandidateWord> { it.dimensions.maxOfOrNull { d -> d.overdueDays } ?: 0.0 }
                .thenBy { it.dimensions.minOfOrNull { d -> stateRank(d.state) } ?: 99 }
                .thenBy { it.dimensions.mapNotNull { d -> d.lastAt }.minOrNull() ?: 0L }
                .thenBy { it.wordId }
        )
    }

    private fun sortWeakPool(weak: List<CandidateWord>): List<CandidateWord> {
        return weak.sortedWith(
            compareByDescending<CandidateWord> { it.dimensions.sumOf { d -> d.recentErrors } }
                .thenByDescending { it.dimensions.sumOf { d -> d.assistedCount } }
                .thenBy { it.wordId }
        )
    }

    private fun sortFreshPool(fresh: List<CandidateWord>): List<CandidateWord> {
        return fresh.sortedWith(
            compareByDescending<CandidateWord> { it.baseScore }
                .thenBy { it.length }
                .thenBy { it.wordId }
        )
    }

    private fun sortConsolidatePool(consolidate: List<CandidateWord>): List<CandidateWord> {
        return consolidate.sortedWith(
            compareBy<CandidateWord> { it.dimensions.mapNotNull { d -> d.effectiveDue }.minOrNull() ?: Long.MAX_VALUE }
                .thenBy { it.wordId }
        )
    }

    private fun stateRank(state: String): Int = when (state) {
        "learning" -> 1
        "shaky" -> 2
        "solid" -> 3
        else -> 4
    }

    private fun dimensionTieRank(dimension: String): Int {
        val idx = DIMENSION_TIE_BREAKER.indexOf(dimension)
        return if (idx >= 0) idx else 99
    }

    /** 组装 Due 词条环节与理由 */
    private fun assembleDueItem(cand: CandidateWord, material: MaterialStatus?): ProposedItem {
        val sortedDims = cand.dimensions
            .filter { it.effectiveDue != null }
            .sortedWith(
                compareByDescending<CandidateDimension> { it.overdueDays }
                    .thenBy { stateRank(it.state) }
                    .thenBy { dimensionTieRank(it.dimension) }
            )
            .take(PlanConstraints.MAX_STAGES_PER_WORD)

        val stages = mutableListOf<ProposedStage>()
        for (dim in sortedDims) {
            val stage = when (dim.dimension) {
                EventDimension.SPELLING.key -> {
                    ProposedStage("WEAK_PRACTICE", dim.dimension, PlanConstraints.CREDIT_WEAK_PRACTICE)
                }
                EventDimension.LISTENING.key -> {
                    if (material?.hasAudio == true) {
                        ProposedStage("PRACTICE_BASIC", dim.dimension, PlanConstraints.CREDIT_PRACTICE_BASIC, listening = true)
                    } else {
                        // 无音频降级为拼写练习
                        ProposedStage("WEAK_PRACTICE", EventDimension.SPELLING.key, PlanConstraints.CREDIT_WEAK_PRACTICE)
                    }
                }
                EventDimension.MEANING.key -> {
                    if (dim.state == "solid") {
                        ProposedStage("REVIEW", dim.dimension, PlanConstraints.CREDIT_REVIEW)
                    } else if (material?.distractorCount ?: 0 >= 2) {
                        ProposedStage("PRACTICE_BASIC", dim.dimension, PlanConstraints.CREDIT_PRACTICE_BASIC)
                    } else {
                        // 缺干扰项降级为回忆自评
                        ProposedStage("REVIEW", dim.dimension, PlanConstraints.CREDIT_REVIEW)
                    }
                }
                EventDimension.PRONUNCIATION.key -> {
                    ProposedStage("ORAL", dim.dimension, PlanConstraints.CREDIT_ORAL)
                }
                else -> ProposedStage("REVIEW", dim.dimension, PlanConstraints.CREDIT_REVIEW)
            }
            stages.add(stage)
        }

        val maxOverdue = sortedDims.maxOfOrNull { it.overdueDays } ?: 0.0
        val isSolidDecay = sortedDims.any { it.state == "solid" && it.overdueDays >= 60.0 }
        val isShakyDecay = sortedDims.any { it.state == "shaky" && it.overdueDays >= 30.0 }

        val (reasonCode, reasonText) = when {
            isSolidDecay -> "due_decay_solid" to "掌握已超过 60 天未检验，安排抽测自评"
            isShakyDecay -> "due_decay_shaky" to "掌握不够稳固，提前安排复习"
            maxOverdue >= 1.0 -> "due_overdue" to "已逾期 ${maxOverdue.toInt()} 天，需要及时巩固"
            else -> "due_today" to "今日到期复习，保持记忆连贯"
        }

        return ProposedItem(
            wordId = cand.wordId,
            english = cand.english,
            meaning = cand.meaning,
            example = "",
            translation = "",
            reasonCode = reasonCode,
            reasonText = reasonText,
            stages = stages
        )
    }

    /** 组装 Weak 词条环节与理由 */
    private fun assembleWeakItem(cand: CandidateWord, material: MaterialStatus?): ProposedItem {
        val sortedDims = cand.dimensions
            .sortedWith(
                compareByDescending<CandidateDimension> { it.recentErrors }
                    .thenByDescending { it.assistedCount }
                    .thenBy { stateRank(it.state) }
                    .thenBy { dimensionTieRank(it.dimension) }
            )
            .take(1) // 薄弱词专注单项突破

        val targetDim = sortedDims.firstOrNull()?.dimension ?: EventDimension.SPELLING.key
        val stage = when (targetDim) {
            EventDimension.SPELLING.key -> ProposedStage("WEAK_PRACTICE", targetDim, PlanConstraints.CREDIT_WEAK_PRACTICE)
            EventDimension.MEANING.key -> {
                if (material?.distractorCount ?: 0 >= 2) {
                    ProposedStage("WEAK_PRACTICE", targetDim, PlanConstraints.CREDIT_WEAK_PRACTICE)
                } else {
                    ProposedStage("WEAK_PRACTICE", EventDimension.SPELLING.key, PlanConstraints.CREDIT_WEAK_PRACTICE)
                }
            }
            EventDimension.LISTENING.key -> {
                if (material?.hasAudio == true) {
                    ProposedStage("PRACTICE_BASIC", targetDim, PlanConstraints.CREDIT_PRACTICE_BASIC, listening = true)
                } else {
                    ProposedStage("WEAK_PRACTICE", EventDimension.SPELLING.key, PlanConstraints.CREDIT_WEAK_PRACTICE)
                }
            }
            else -> ProposedStage("ORAL", targetDim, PlanConstraints.CREDIT_ORAL)
        }

        val errors = sortedDims.firstOrNull()?.recentErrors ?: 1
        return ProposedItem(
            wordId = cand.wordId,
            english = cand.english,
            meaning = cand.meaning,
            example = "",
            translation = "",
            reasonCode = "weak_error",
            reasonText = "近期出现 $errors 次错误，针对性巩固练习",
            stages = listOf(stage)
        )
    }

    /** 组装 Fresh 词条环节与理由 */
    private fun assembleFreshItem(cand: CandidateWord, material: MaterialStatus?): ProposedItem {
        val stages = mutableListOf<ProposedStage>()
        stages.add(ProposedStage("LEARN", EventDimension.MEANING.key, PlanConstraints.CREDIT_LEARN))

        if (material?.hasClozeGap == true) {
            stages.add(ProposedStage("PRACTICE_BASIC", EventDimension.MEANING.key, PlanConstraints.CREDIT_PRACTICE_BASIC))
        } else {
            // 缺例句或干扰项时，降级为新词盲写练习
            stages.add(ProposedStage("PRACTICE_BASIC", EventDimension.SPELLING.key, PlanConstraints.CREDIT_PRACTICE_BASIC))
        }

        val (reasonCode, reasonText) = if (cand.desktopCommitted > 0) {
            "fresh_desktop" to "电脑端已输入 ${cand.desktopCommitted} 次，首次在手机学习"
        } else {
            "fresh_new" to "新加入词库，开始初次系统学习"
        }

        return ProposedItem(
            wordId = cand.wordId,
            english = cand.english,
            meaning = cand.meaning,
            example = "",
            translation = "",
            reasonCode = reasonCode,
            reasonText = reasonText,
            stages = stages
        )
    }

    /** 组装 Consolidate 词条环节与理由 */
    private fun assembleConsolidateItem(cand: CandidateWord, material: MaterialStatus?): ProposedItem {
        val stage = ProposedStage("CONSOLIDATE_PRACTICE", EventDimension.MEANING.key, PlanConstraints.CREDIT_CONSOLIDATE_PRACTICE)
        return ProposedItem(
            wordId = cand.wordId,
            english = cand.english,
            meaning = cand.meaning,
            example = "",
            translation = "",
            reasonCode = "consolidate_soon",
            reasonText = "掌握良好，提前进行日常保温练习",
            stages = listOf(stage)
        )
    }

    fun assembleCandidatePools(
        words: List<VocabularyEntry>,
        learnerEntries: List<LearnerModelEntry>,
        overrides: Map<Pair<Long, String>, UserOverride>,
        desktopCommittedMap: Map<Long, Int> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis()
    ): CandidatePools {
        val entriesByWord = learnerEntries.groupBy { it.wordId }
        val dueList = mutableListOf<CandidateWord>()
        val weakList = mutableListOf<CandidateWord>()
        val freshList = mutableListOf<CandidateWord>()
        val consolidateList = mutableListOf<CandidateWord>()

        for (word in words) {
            val entries = entriesByWord[word.id].orEmpty()
            val dimensions = entries.map { entry ->
                val override = overrides[word.id to entry.dimension]
                val effectiveDue = EffectiveDue.calculate(entry.state, entry.nextDue, entry.lastAt, nowMillis)
                val overdueDays = if (effectiveDue != null && nowMillis >= effectiveDue) {
                    (nowMillis - effectiveDue) / 86400000.0
                } else 0.0
                val recentErrors = (entry.attempts - entry.correct).coerceAtLeast(0)
                CandidateDimension(
                    dimension = entry.dimension,
                    state = if (override?.isValid(nowMillis) == true) "solid" else entry.state,
                    effectiveDue = effectiveDue,
                    overdueDays = overdueDays,
                    recentErrors = recentErrors,
                    assistedCount = entry.assisted,
                    lastAt = entry.lastAt
                )
            }

            val committed = desktopCommittedMap[word.id] ?: 0
            val totalAttempts = entries.sumOf { it.attempts }
            val totalCorrect = entries.sumOf { it.correct }
            val errorRate = if (totalAttempts > 0) (totalAttempts - totalCorrect).toDouble() / totalAttempts else null
            val baseScore = ContextPack.calculateBaseScore(committed, word.lastSeen, nowMillis, errorRate)

            val cand = CandidateWord(
                wordId = word.id,
                english = word.english,
                meaning = word.meaning,
                glossOrigin = word.glossOrigin,
                dimensions = dimensions,
                desktopCommitted = committed,
                desktopLastSeenMillis = word.lastSeen,
                desktopKnown = false,
                phoneErrorRate = errorRate,
                baseScore = baseScore
            )

            val hasDue = dimensions.any { it.effectiveDue != null && it.effectiveDue <= nowMillis }
            val isWeak = !hasDue && dimensions.any { (it.state == "learning" || it.state == "shaky") && it.recentErrors >= 2 }
            val isAllUnknown = dimensions.isEmpty() || dimensions.all { it.state == "unknown" }
            val isConsolidate = !hasDue && !isWeak && !isAllUnknown && dimensions.any { it.state == "solid" && it.effectiveDue != null && it.effectiveDue <= nowMillis + 7 * 86400000L }

            when {
                hasDue -> dueList.add(cand)
                isWeak -> weakList.add(cand)
                isAllUnknown && word.meaning.isNotBlank() -> freshList.add(cand)
                isConsolidate -> consolidateList.add(cand)
            }
        }

        return CandidatePools(dueList, weakList, freshList, consolidateList)
    }

    /** 从数据库读取实际状态，纯本地生成并保存新格式今日计划 */
    fun buildAndSave(context: Context, nowMillis: Long = System.currentTimeMillis()): Plan? {
        val store = org.qingjian.speech.learning.LearningStore.get(context)
        val plans = PlanStore(context)
        val prefs = org.qingjian.speech.learning.StudyPreferences(context)
        val words = store.allWords()
        if (words.isEmpty()) return null

        val learnerStore = org.qingjian.speech.learning.learner.LearnerModelStore(store.writableDatabase)
        val allEntries = mutableListOf<LearnerModelEntry>()
        for (w in words) {
            allEntries.addAll(learnerStore.forWord(w.id))
        }
        val overrides = learnerStore.activeOverrides()

        val pools = assembleCandidatePools(words, allEntries, overrides, emptyMap(), nowMillis)
        val (factor, factorReason) = ContextPack.deriveFactor(plans.recentHistory(), prefs.revision)
        val usable = PlanConstraints.calculateUsableCredit(prefs.minutes, factor)
        val limits = PlanConstraints.resolveLimits(
            usableCredit = usable,
            preferenceNewWords = prefs.newWords,
            isBeginner = prefs.level.startsWith("基础较弱"),
            freshCount = pools.fresh.size,
            freshOnlyDesktop = pools.fresh.isNotEmpty() && pools.fresh.all { it.desktopCommitted > 0 }
        )

        val pack = ContextPack(
            today = PlanStore.today(),
            epoch = plans.epoch(),
            preferencesRevision = prefs.revision,
            desktopRevision = System.currentTimeMillis(),
            modelRevision = 1,
            budget = BudgetSnapshot(prefs.minutes, factor, factorReason, usable),
            candidates = pools,
            materials = emptyMap(),
            totalWords = words.size
        )

        val proposed = build(pack, limits)
        if (proposed.items.isEmpty()) return null
        return plans.createV2(proposed, plans.epoch())
    }

    private fun buildBudgetJson(budget: BudgetSnapshot, limits: PlanLimits): String {
        return JSONObject()
            .put("minutes", budget.minutes)
            .put("factor", budget.factor)
            .put("factor_reason", budget.factorReason)
            .put("usable_credit", limits.usableCredit)
            .put("due_target", limits.dueTarget)
            .put("due_cap", limits.dueCap)
            .put("new_word_cap", limits.newWordCap)
            .toString()
    }
}
