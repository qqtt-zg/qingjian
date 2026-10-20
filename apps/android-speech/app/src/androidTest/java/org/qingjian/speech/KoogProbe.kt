//! 隔离设备验证真正的 Koog 图调度、混合工具回复、取消、纯文字兼容和受控错误。
package org.qingjian.speech

import android.app.Instrumentation
import io.github.oshai.kotlinlogging.KotlinLogging
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.Teaching
import org.qingjian.speech.learning.ai.tools.ActionStore
import org.qingjian.speech.learning.ai.tools.Runner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

object KoogProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val runner = Runner(app)
        check(!KotlinLogging.logger("ai.koog.privacy-test").isErrorEnabled())
        val word = store.addWord("control"); store.setMeaning(word, "控制")
        val id = store.question("general", "查一下 control")
        val bodies = mutableListOf<JSONObject>()
        var sawKoog = false
        var status = 200
        var cancel = false
        var cancelAfterResponse = false
        var responses = listOf(
            """{"choices":[{"message":{"content":"我先查词。","tool_calls":[{"id":"lookup-one","type":"function","function":{"name":"search_vocabulary","arguments":"{\"query\":\"control\",\"limit\":1}"}}]}}]}""",
            """{"choices":[{"message":{"content":"control 是控制。"}}]}""")
        fun client() = Client { url -> object : HttpURLConnection(url) {
            val sent = ByteArrayOutputStream()
            var response = ""
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = sent
            override fun getResponseCode(): Int {
                sawKoog = sawKoog || Thread.currentThread().stackTrace.any { it.className.startsWith("ai.koog.") }
                response = responses[bodies.size.coerceAtMost(responses.lastIndex)]
                bodies += JSONObject(sent.toString("UTF-8"))
                return status
            }
            override fun getInputStream(): ByteArrayInputStream {
                if(cancelAfterResponse) cancel = true
                return ByteArrayInputStream(response.toByteArray())
            }
        } }
        val profile = Profile("https://koog.invalid/v1", "user-custom-model", "fake-key")
        fun run(enabled: Boolean = true) = runner.complete(client(), profile, Teaching.messages(null, emptyList(), "查一下 control"),
            "general", id, { cancel }, {}, enabled)
        check(run() == "control 是控制。" && sawKoog && bodies.size == 2)
        check(bodies.first().getString("model") == profile.model && bodies.first().getJSONArray("tools").length() == 5)
        val followup = bodies.last().getJSONArray("messages")
        val result = (0 until followup.length()).map { followup.getJSONObject(it) }.single { it.getString("role") == "tool" }
        check(result.getString("tool_call_id") == "lookup-one" && result.getString("content").contains("控制"))
        check(store.words().size == 1 && ActionStore(app).forTopic("general").isEmpty())
        bodies.clear(); responses = listOf("""{"choices":[{"message":{"content":"普通回答"}}]}""")
        check(run(false) == "普通回答" && bodies.size == 1 && !bodies.single().has("tools"))
        bodies.clear(); status = 401
        check(runCatching { run() }.exceptionOrNull()?.message?.contains("401") == true && bodies.size == 1)
        status = 200; bodies.clear()
        responses = listOf("""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"unknown","type":"function","function":{"name":"delete_all_data","arguments":"{}"}}]}}]}""",
            """{"choices":[{"message":{"content":"没有这个工具。"}}]}""")
        check(run() == "没有这个工具。" && bodies.size == 2 && store.word(word) != null)
        bodies.clear(); cancelAfterResponse = true
        responses = listOf("""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"cancel-one","type":"function","function":{"name":"prepare_word_card","arguments":"{\"english\":\"help\",\"meaning\":\"帮助\",\"example\":\"Help me.\",\"translation\":\"帮助我。\"}"}}]}}]}""")
        check(runCatching { run() }.exceptionOrNull() is CancellationException)
        check(bodies.size == 1 && ActionStore(app).forTopic("general").isEmpty() && store.words("help").isEmpty())
        store.clearLearning()
        return "PASS Koog: SDK graph on actual stack, mixed text/tool executes lookup, stable tool IDs, custom model, plain mode without tools, original 401 error, unknown tool denied, cancel before draft and disabled content logs"
    }
}
