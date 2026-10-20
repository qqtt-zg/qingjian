//! 可跨旋转保留的每日 Agent 请求；只从用户行动启动，退出取消，失败不自动重试。
package org.qingjian.speech.learning.daily

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Credentials
import java.util.concurrent.Executors

class Job(context: Context, var planId: Long) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var client: Client? = null
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    var busy = false
        private set
    var notice = ""
        private set
    var changed: (() -> Unit)? = null

    fun start(mode: String, instruction: String = "") {
        if(busy || closed) return
        cancelled=false; busy=true; notice=when(mode) { "CREATE" -> "正在安排今天的学习…"; "ADAPT" -> "正在根据实际作答调整…"; else -> "正在生成本轮回顾…" }
        val request = Client().apply {task=when(mode){"CREATE"->"每日学习 · 安排";"ADAPT"->"每日学习 · 调整";else->"每日学习 · 回顾"}}; client=request; changed?.invoke()
        worker.execute {
            val result = runCatching { Agent(app).run(mode, planId, instruction, request, Credentials(app).load(), { cancelled || closed }) { stage ->
                main.post { if(!closed && !cancelled && busy) { notice=stage; changed?.invoke() } }
            } }
            main.post {
                busy=false; client=null
                result.onSuccess { planId=it.id; notice="已根据真实资料更新，可继续学习" }
                    .onFailure { notice=if(cancelled) "本次已取消，原学习进度保留" else Client.errorMessage(it as? Exception ?: IllegalStateException("学习请求失败")) }
                if(!closed) changed?.invoke()
            }
        }
    }

    fun cancel() { cancelled=true; client?.cancel(); notice="正在取消…"; changed?.invoke() }
    fun close() { closed=true; changed=null; cancel(); worker.shutdown() }
}
