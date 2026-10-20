//! 单词材料快照；学习浏览和回忆自评分别保存。
package org.qingjian.speech.learning.daily

data class PlanItem(
    val id: Long,

    val wordId: Long,

    val english: String,

    val meaning: String,

    val example: String,

    val translation: String,

    val purpose: String,

    val learned: Boolean,

    val rating: String,
)
