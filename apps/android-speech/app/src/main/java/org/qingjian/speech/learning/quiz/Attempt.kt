//! 一次已经提交的答案；题目快照保留原笔记，后续编辑不改写历史成绩。
package org.qingjian.speech.learning.quiz

data class Attempt(
    val key: String,

    val wordId: Long,

    val english: String,

    val meaning: String,

    val kind: Kind,

    val answer: String,

    val correct: Boolean,
)
