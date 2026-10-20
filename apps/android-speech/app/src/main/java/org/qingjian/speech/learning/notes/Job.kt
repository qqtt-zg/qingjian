//! 手机 AI 的整包补全任务；旋转保留、退出取消，晚到结果只在原资料未变化时写回。
package org.qingjian.speech.learning.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.tools.ToolSettings
import java.util.concurrent.Executors

class Job(context: Context, private val wordId: Long) {
    private val app=context.applicationContext
    private val store=Store(app)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var client: Client?=null
    @Volatile private var closed=false
    @Volatile private var cancelled=false
    private var beforeApplied: Snapshot?=null
    private var applied: Snapshot?=null
    var busy=false
        private set
    var notice=""
        private set
    var needsConfiguration=false
        private set
    var changed:(()->Unit)?=null
    val canUndo get()=applied?.let {store.snapshot(wordId)==it}==true

    fun start() {
        if(busy || closed) return
        val before=store.snapshot(wordId) ?: return
        if(store.complete(wordId)) {notice="学习资料已完整，可直接查看或继续向 AI 提问";changed?.invoke();return}
        if(before.english.length>100 || !before.english.any {it in 'a'..'z' || it in 'A'..'Z'}) {
            notice="此功能适用于 1～100 字的英文单词或短语";changed?.invoke();return
        }
        val profile=runCatching {Credentials(app).load().also {it.validate()}}.getOrNull()
        needsConfiguration=profile==null
        if(profile==null) {notice="请先在手机设置中配置 AI 地址、模型和密钥，无需连接电脑";changed?.invoke();return}
        val tools=ToolSettings.enabled(app)
        val request=Client().apply {task="学习资料补全"};client=request;cancelled=false;busy=true;notice="手机 AI 正在准备学习资料…";changed?.invoke()
        worker.execute {
            val result=runCatching {Agent(app).run(before,request,profile,tools,{closed || cancelled}) {stage ->
                main.post {if(!closed && !cancelled && busy && notice!=stage) {notice=stage;changed?.invoke()}}
            }}
            main.post {
                busy=false;client=null
                if(closed) return@post
                if(cancelled) notice="已取消，本次学习资料未保存；已发出的请求可能仍产生服务用量"
                else result.onSuccess {pack ->
                    val current=runCatching {Credentials(app).load()}.getOrNull()
                    if(current==null || current.address!=profile.address || current.model!=profile.model || current.key!=profile.key) {
                        notice="手机 AI 配置已更换，本次结果未保存"
                    } else {
                        val saved=runCatching {store.apply(before,pack)}
                        applied=saved.getOrNull();beforeApplied=if(applied!=null)before else null
                        notice=when {
                            saved.isFailure -> "学习资料保存失败，原内容保留，请检查手机存储空间后重试"
                            applied!=null -> "已补全 · 手机 AI\n释义、双语例句、常用搭配与用法已保存，可离线查看。"
                            else -> "原笔记或资料已变化，本次结果未覆盖"
                        }
                    }
                }.onFailure {error ->
                    notice=if(error is org.json.JSONException) "AI 返回的学习资料不完整，整包未保存，请手动重试"
                        else Client.errorMessage(error as? Exception ?: IllegalStateException("资料补全失败"))
                }
                changed?.invoke()
            }
        }
    }

    fun undo() {
        val value=applied ?: return;val before=beforeApplied ?: return
        val restored=runCatching {store.undo(before,value)}
        if(restored.isFailure) {notice="撤销未完成，原资料保留，请检查手机存储后重试";changed?.invoke();return}
        notice=if(restored.getOrNull()!=null) "已撤销本次补全，原笔记与原资料保留" else "笔记或资料已修改，本次未撤销"
        applied=null;beforeApplied=null;changed?.invoke()
    }

    fun cancel() {cancelled=true;client?.cancel();notice="正在取消…";changed?.invoke()}
    fun edited() {applied=null;beforeApplied=null;if(!busy)notice="中文笔记已保存";changed?.invoke()}
    fun close() {closed=true;changed=null;cancel();worker.shutdown()}
}
