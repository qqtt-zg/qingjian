//! 一轮持久学习计划；进度与成绩由应用维护，模型只提供材料和建议。
package org.qingjian.speech.learning.daily

data class Plan(
    val id: Long,

    val day: String,

    val title: String,

    val reason: String,

    val phase: String,

    val cursor: Int,

    val revision: Int,

    val adjustment: String,

    val reflection: String,
) {
    private val adjustmentData get() = runCatching {org.json.JSONObject(adjustment)}.getOrElse {org.json.JSONObject().put("reason",adjustment)}
    val adjustmentReason get() = adjustmentData.optString("reason")
    val userInstruction get() = adjustmentData.optString("user_instruction")
    val questionLimit get() = adjustmentData.optInt("question_limit",0)
}
