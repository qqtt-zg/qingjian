//! 每日练习/小测题目快照；保存待提交选择、辅助和音频请求次数。
package org.qingjian.speech.learning.daily

import org.qingjian.speech.learning.quiz.Question

data class StudyQuestion(
    val question: Question,

    val listening: Boolean,

    val draft: String,

    val assisted: Boolean,

    val answer: String,

    val replays: Int,
) {
    val correct get() = answer.isNotEmpty() && question.correct(answer)
}
