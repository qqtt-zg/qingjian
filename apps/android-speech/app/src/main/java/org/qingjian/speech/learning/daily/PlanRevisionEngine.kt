//! 计划修订引擎：逐条原子校验模型修订提案（drop/swap/reorder/add），保障已完成环节不可变与全局硬约束。
package org.qingjian.speech.learning.daily

import org.json.JSONObject

sealed class PlanOperation {
    data class Drop(val wordId: Long, val reason: String = "") : PlanOperation()
    data class Swap(val fromWordId: Long, val toWordId: Long, val reason: String = "") : PlanOperation()
    data class Reorder(val wordIds: List<Long>, val reason: String = "") : PlanOperation()
    data class Add(val wordId: Long, val reason: String = "") : PlanOperation()

    companion object {
        fun fromJson(json: JSONObject): PlanOperation {
            val type = json.optString("type")
            return when (type) {
                "drop" -> Drop(json.getLong("word_id"), json.optString("reason"))
                "swap" -> Swap(json.getLong("from_word_id"), json.getLong("to_word_id"), json.optString("reason"))
                "reorder" -> {
                    val arr = json.getJSONArray("word_ids")
                    val list = (0 until arr.length()).map { arr.getLong(it) }
                    Reorder(list, json.optString("reason"))
                }
                "add" -> Add(json.getLong("word_id"), json.optString("reason"))
                else -> error("未知的计划操作类型: $type")
            }
        }
    }
}

sealed class OperationCheckResult {
    data class Accepted(val operation: PlanOperation, val newPlan: ProposedPlan) : OperationCheckResult()
    data class Rejected(val operation: PlanOperation, val reason: String) : OperationCheckResult()
}

data class PlanRevisionRecord(
    val planId: Long,
    val revisionNo: Int,
    val action: String,
    val beforeSnapshot: String,
    val afterSnapshot: String,
    val userConstraints: String,
    val reason: String,
    val proposedOpsCount: Int,
    val acceptedOpsCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class RevisionEngineOutcome {
    data class Success(
        val revisedPlan: ProposedPlan,
        val acceptedCount: Int,
        val rejectedCount: Int,
        val rejectedReasons: List<String>,
        val auditRecord: PlanRevisionRecord? = null
    ) : RevisionEngineOutcome() {
        val adoptionRate: Double get() = if (acceptedCount + rejectedCount > 0) {
            acceptedCount.toDouble() / (acceptedCount + rejectedCount)
        } else 1.0
    }

    data class Abandoned(
        val planId: Long,
        val reason: String
    ) : RevisionEngineOutcome()
}

object PlanRevisionEngine {

    /**
     * 单操作原子校验。
     */
    fun checkOperation(
        currentPlan: ProposedPlan,
        op: PlanOperation,
        immutableWordIds: Set<Long>,
        limits: PlanLimits,
        pools: CandidatePools,
        materials: Map<Long, MaterialStatus> = emptyMap()
    ): OperationCheckResult {
        val workingItems = currentPlan.items.toMutableList()
        val allCandidates = (pools.due + pools.weak + pools.fresh + pools.consolidate).associateBy { it.wordId }

        when (op) {
            is PlanOperation.Drop -> {
                if (op.wordId in immutableWordIds) {
                    return OperationCheckResult.Rejected(op, "词条 ID=${op.wordId} 包含已完成或已跳过的环节，禁止修改或移除")
                }
                val idx = workingItems.indexOfFirst { it.wordId == op.wordId }
                if (idx < 0) {
                    return OperationCheckResult.Rejected(op, "词条 ID=${op.wordId} 不在当前待做计划中")
                }
                val candidateItems = workingItems.toMutableList().apply { removeAt(idx) }
                val testPlan = currentPlan.copy(
                    items = candidateItems,
                    plannedCredit = candidateItems.sumOf { it.totalCredit }
                )
                val check = PlanConstraints.validate(testPlan, limits)
                return if (check is ValidationResult.Success) {
                    OperationCheckResult.Accepted(op, testPlan)
                } else {
                    OperationCheckResult.Rejected(op, (check as ValidationResult.Fail).reason)
                }
            }

            is PlanOperation.Swap -> {
                if (op.fromWordId in immutableWordIds) {
                    return OperationCheckResult.Rejected(op, "被替换词条 ID=${op.fromWordId} 包含已完成或已跳过的环节，禁止替换")
                }
                val fromIdx = workingItems.indexOfFirst { it.wordId == op.fromWordId }
                if (fromIdx < 0) {
                    return OperationCheckResult.Rejected(op, "被替换词条 ID=${op.fromWordId} 不在待做列表中")
                }
                if (workingItems.any { it.wordId == op.toWordId }) {
                    return OperationCheckResult.Rejected(op, "目标词条 ID=${op.toWordId} 已在当前计划中")
                }
                val targetCand = allCandidates[op.toWordId]
                    ?: return OperationCheckResult.Rejected(op, "目标词条 ID=${op.toWordId} 不在可用候选池中")

                // 根据候选类型生成合适环节
                val replacement = assembleCandidateItem(targetCand, pools, materials[op.toWordId])
                val candidateItems = workingItems.toMutableList().apply { set(fromIdx, replacement) }
                val testPlan = currentPlan.copy(
                    items = candidateItems,
                    plannedCredit = candidateItems.sumOf { it.totalCredit }
                )
                val check = PlanConstraints.validate(testPlan, limits)
                return if (check is ValidationResult.Success) {
                    OperationCheckResult.Accepted(op, testPlan)
                } else {
                    OperationCheckResult.Rejected(op, (check as ValidationResult.Fail).reason)
                }
            }

            is PlanOperation.Reorder -> {
                val pendingItems = workingItems.filter { it.wordId !in immutableWordIds }
                val pendingIds = pendingItems.map { it.wordId }.toSet()
                if (op.wordIds.size != pendingIds.size || op.wordIds.toSet() != pendingIds) {
                    return OperationCheckResult.Rejected(op, "重排列表必须严格覆盖且仅覆盖所有剩余待做条目")
                }
                val itemMap = pendingItems.associateBy { it.wordId }
                val reorderedPending = op.wordIds.mapNotNull { itemMap[it] }
                val candidateItems = workingItems.filter { it.wordId in immutableWordIds } + reorderedPending
                val testPlan = currentPlan.copy(
                    items = candidateItems,
                    plannedCredit = candidateItems.sumOf { it.totalCredit }
                )
                return OperationCheckResult.Accepted(op, testPlan)
            }

            is PlanOperation.Add -> {
                if (workingItems.any { it.wordId == op.wordId }) {
                    return OperationCheckResult.Rejected(op, "词条 ID=${op.wordId} 已在计划中")
                }
                val targetCand = allCandidates[op.wordId]
                    ?: return OperationCheckResult.Rejected(op, "词条 ID=${op.wordId} 不在候选池中")

                val newItem = assembleCandidateItem(targetCand, pools, materials[op.wordId])
                val candidateItems = workingItems.toMutableList().apply { add(newItem) }
                val testPlan = currentPlan.copy(
                    items = candidateItems,
                    plannedCredit = candidateItems.sumOf { it.totalCredit }
                )
                val check = PlanConstraints.validate(testPlan, limits)
                return if (check is ValidationResult.Success) {
                    OperationCheckResult.Accepted(op, testPlan)
                } else {
                    OperationCheckResult.Rejected(op, (check as ValidationResult.Fail).reason)
                }
            }
        }
    }

    /**
     * 批量应用修订操作列表，记录接受/拒绝项及原因。
     */
    fun applyOperations(
        basePlan: ProposedPlan,
        operations: List<PlanOperation>,
        immutableWordIds: Set<Long>,
        limits: PlanLimits,
        pools: CandidatePools,
        materials: Map<Long, MaterialStatus> = emptyMap(),
        explanation: String = "",
        userConstraintText: String = "",
        planId: Long = 0L,
        revisionNo: Int = 0
    ): RevisionEngineOutcome {
        // 用户显式要求减至 0 题时，显式判定为放弃
        if (limits.questionLimit != null && limits.questionLimit <= 0) {
            return RevisionEngineOutcome.Abandoned(planId, "依据用户约束，已安排结束本轮")
        }

        var working = basePlan
        var accepted = 0
        var rejected = 0
        val reasons = mutableListOf<String>()

        for (op in operations) {
            when (val check = checkOperation(working, op, immutableWordIds, limits, pools, materials)) {
                is OperationCheckResult.Accepted -> {
                    working = check.newPlan
                    accepted++
                }
                is OperationCheckResult.Rejected -> {
                    rejected++
                    reasons.add("${op}: ${check.reason}")
                }
            }
        }

        // 若最终计划被减至 0 项
        if (working.items.isEmpty()) {
            return RevisionEngineOutcome.Abandoned(planId, "修订后本轮无剩余条目，已结束本轮")
        }

        // 最终全局复核
        val finalCheck = PlanConstraints.validate(working, limits)
        if (finalCheck !is ValidationResult.Success) {
            // 全局不合规，整轮驳回
            return RevisionEngineOutcome.Success(
                revisedPlan = basePlan,
                acceptedCount = 0,
                rejectedCount = operations.size,
                rejectedReasons = listOf("修订结果违反计划约束：${(finalCheck as ValidationResult.Fail).reason}")
            )
        }

        val audit = PlanRevisionRecord(
            planId = planId,
            revisionNo = revisionNo,
            action = "MODEL_REVISE",
            beforeSnapshot = basePlan.items.map { it.english }.joinToString(),
            afterSnapshot = working.items.map { it.english }.joinToString(),
            userConstraints = userConstraintText,
            reason = explanation.ifBlank { "模型自适应修订" },
            proposedOpsCount = operations.size,
            acceptedOpsCount = accepted
        )

        return RevisionEngineOutcome.Success(
            revisedPlan = working,
            acceptedCount = accepted,
            rejectedCount = rejected,
            rejectedReasons = reasons,
            auditRecord = audit
        )
    }

    private fun assembleCandidateItem(
        cand: CandidateWord,
        pools: CandidatePools,
        material: MaterialStatus?
    ): ProposedItem {
        return when {
            pools.due.any { it.wordId == cand.wordId } -> {
                ProposedItem(
                    wordId = cand.wordId,
                    english = cand.english,
                    meaning = cand.meaning,
                    example = "",
                    translation = "",
                    reasonCode = "due_revision",
                    reasonText = "到期复习项",
                    stages = listOf(ProposedStage("REVIEW", "meaning", PlanConstraints.CREDIT_REVIEW))
                )
            }
            pools.weak.any { it.wordId == cand.wordId } -> {
                ProposedItem(
                    wordId = cand.wordId,
                    english = cand.english,
                    meaning = cand.meaning,
                    example = "",
                    translation = "",
                    reasonCode = "weak_revision",
                    reasonText = "薄弱强化项",
                    stages = listOf(ProposedStage("WEAK_PRACTICE", "spelling", PlanConstraints.CREDIT_WEAK_PRACTICE))
                )
            }
            else -> {
                ProposedItem(
                    wordId = cand.wordId,
                    english = cand.english,
                    meaning = cand.meaning,
                    example = "",
                    translation = "",
                    reasonCode = "fresh_revision",
                    reasonText = "模型推荐新词",
                    stages = listOf(
                        ProposedStage("LEARN", "meaning", PlanConstraints.CREDIT_LEARN),
                        ProposedStage("PRACTICE_BASIC", "spelling", PlanConstraints.CREDIT_PRACTICE_BASIC)
                    )
                )
            }
        }
    }
}
