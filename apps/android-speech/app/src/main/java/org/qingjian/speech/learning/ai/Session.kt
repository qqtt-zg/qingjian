//! 可跨界面旋转保留的单次答疑任务；问题先落盘，失败后仅手动重试。
package org.qingjian.speech.learning.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.qingjian.speech.learning.LearningStore
import java.util.concurrent.Executors

class Session(context: Context, val wordId: Long, private val createClient: () -> Client = { Client() }) {
    private val app = context.applicationContext
    private val store = LearningStore.get(app)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var client: Client? = null
    private var closed = false
    val topic = if(wordId > 0) "word:$wordId" else "general"
    var busy = false
        private set
    var notice = ""
        private set
    var changed: (() -> Unit)? = null

    fun send(question: String, retryId: Long? = null) {
        if (busy || closed) return
        if(question.isBlank() || question.length > 4000) { notice = "请输入 1～4000 字的问题"; changed?.invoke(); return }
        val history = store.messages(topic)
        val id = retryId ?: store.question(topic, question.trim())
        busy = true; notice = "正在请求 AI…"; changed?.invoke()
        val request = createClient(); client = request
        executor.execute {
            val message = try {
                val profile = Credentials(app).load()
                val word = if(wordId > 0) store.word(wordId) else null
                val answer = request.complete(profile, Teaching.messages(word, history, question))
                store.answer(topic, id, answer)
                "回答已保存，可离线查看；AI 解释仍需结合语境核实"
            } catch(error: Exception) {
                store.failed(id)
                Client.errorMessage(error) + "；问题已保存在手机"
            }
            handler.post {
                busy = false; client = null; notice = message
                if(!closed) changed?.invoke()
            }
        }
    }

    fun close() {
        closed = true; changed = null; client?.cancel(); executor.shutdown()
    }
}
