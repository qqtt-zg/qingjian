//! 词汇工作区的内存任务与草稿，页面旋转不重发模型或评测请求。
package org.qingjian.speech.learning.word

import android.content.Context
import org.qingjian.speech.learning.ai.memory.Conversations

class State(context: Context,val wordId: Long) {
    val notes=org.qingjian.speech.learning.notes.Job(context,wordId)
    val oral=org.qingjian.speech.learning.oral.Session(context,wordId)
    val ai=org.qingjian.speech.learning.ai.Session(context,wordId,Conversations(context).selected(wordId))
    val practice=Practice(context,wordId)
    var panel="oral"
    var draft=Conversations(context).draft(ai.topic)
    var sentDraft=""
    var otherDraft=""
    var contextDraft=""
    var teacherOpen=false
    var summaryOpen=false
    var usageOpen=false
    var targetInitialized=false
    fun close(){notes.close();oral.close();ai.close()}
}
