//! Koog 图代理调度模型与白名单工具；每次发送建立独立运行，持久资料继续由本机管理。
package org.qingjian.speech.learning.ai.tools

import android.content.Context
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.ai.ToolCall
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.agent.LearningTool
import org.qingjian.speech.learning.ai.agent.Logging
import org.qingjian.speech.learning.ai.agent.Messages
import org.qingjian.speech.learning.ai.agent.Transport

class Runner(context: Context) {
    init { Logging.disable() }
    private val executor = Executor(context, automatic = true)

    fun complete(client: Client, profile: Profile, messages: JSONArray, topic: String, question: Long,
        cancelled: () -> Boolean, progress: (String) -> Unit, enabled: Boolean = true,
        definitions: JSONArray = Catalog.definitions(), executeLocal: ((ToolCall, String, Long) -> JSONObject)? = null): String = runBlocking {
        val transport = Transport(client, profile, enabled, cancelled, progress, definitions)
        val registry = ToolRegistry {
            if(enabled) {
                for(index in 0 until definitions.length()) tool(LearningTool(definitions.getJSONObject(index), executeLocal ?: executor::execute, topic, question, transport::guard, progress))
            }
        }
        val flow = strategy<Unit, String>("qingjian-learning") {
            val request by node<Unit, Message.Assistant>("request") { llm.writeSession { requestLLM() } }
            val tools by nodeExecuteTools("learningTools", parallel = false)
            val followup by nodeLLMSendToolResults("followup")
            edge(nodeStart forwardTo request)
            edge(request forwardTo tools onToolCalls { true })
            edge(request forwardTo nodeFinish onCondition { it.parts.none { part -> part is MessagePart.Tool.Call } } transformed { it.textContent() })
            edge(tools forwardTo followup)
            edge(followup forwardTo tools onToolCalls { true })
            edge(followup forwardTo nodeFinish onCondition { it.parts.none { part -> part is MessagePart.Tool.Call } } transformed { it.textContent() })
        }
        val model = LLModel(LLMProvider("qingjian-compatible", "用户配置的文字服务"), profile.model,
            listOf(LLMCapability.Completion, LLMCapability.Tools))
        val agent = GraphAIAgent(transport, AIAgentConfig(Messages.prompt(messages), model, maxAgentIterations = 10), flow, registry)
        try { transport.guard(); agent.run(Unit).also { transport.guard(); check(it.isNotBlank()) { "接口没有返回文字回答" } } }
        catch(error: Exception) { throw (transport.failure ?: error) }
        finally { agent.close(); transport.close() }
    }
}
