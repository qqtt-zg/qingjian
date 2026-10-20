//! 一次评测尝试绑定录音与目标；未成功时没有数值成绩。
package org.qingjian.speech.learning.assessment

data class Attempt(val id: String, val recordingId: Long, val reference: String, val mode: String,
    val state: String, val result: Result?, val notice: String, val created: Long)
