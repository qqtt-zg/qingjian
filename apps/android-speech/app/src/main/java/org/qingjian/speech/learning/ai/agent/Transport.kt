//! Koog 的自定义模型执行器；复用已有 HTTPS、响应限额和取消逻辑，限制三次请求。
package org.qingjian.speech.learning.ai.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.Catalog
import java.util.concurrent.CancellationException

class Transport(private val client: Client, private val profile: Profile, private val enabled: Boolean,
    private val cancelled: () -> Boolean, private val progress: (String) -> Unit,
    private val available: JSONArray = Catalog.definitions()) : PromptExecutor() {
    private var requests = 0
    private val deadline = System.nanoTime() + 150_000_000_000L
    var failure: Exception? = null
        private set

    fun guard() {
        if(cancelled()) throw CancellationException().also { failure = it }
        if(System.nanoTime() >= deadline) throw IllegalStateException("学习工具处理超时；已准备的操作可在页面查看").also { failure = it }
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = try {
        guard()
        check(requests < if(enabled) 3 else 1) { "已达到本轮工具步数上限；可查看已有操作卡或缩小问题后重试" }
        val allowCalls = enabled && requests < 2
        val definitions = if(enabled) available.let { all ->
            val names = tools.map { it.name }.toSet()
            val factsFirst = requests==0 && (0 until all.length()).any { all.getJSONObject(it).getJSONObject("function").getString("name")=="get_study_context" }
            JSONArray((0 until all.length()).map { all.getJSONObject(it) }.filter {
                val name=it.getJSONObject("function").getString("name")
                name in names && (!factsFirst || name=="get_study_context")
            })
        } else null
        requests++
        val result = client.turn(profile, Messages.wire(prompt), definitions, allowCalls, progress)
        guard()
        if(definitions!=null && (0 until available.length()).any {available.getJSONObject(it).getJSONObject("function").getString("name")=="get_study_context"}) {
            val offered=(0 until definitions.length()).map {definitions.getJSONObject(it).getJSONObject("function").getString("name")}.toSet()
            check(result.calls.all {it.name in offered}) { "模型请求了本轮未开放的工具，未执行操作" }
        }
        check(allowCalls || result.calls.isEmpty()) { "已达到本轮工具步数上限或已关闭工具；未执行本次调用" }
        Messages.response(result)
    } catch(error: Exception) { failure = error; throw error }

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        error("当前使用完整文字回复，不支持流式执行")

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("当前接口没有配置内容审核能力")

    override fun close() = Unit
}
