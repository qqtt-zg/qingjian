//! 受控请求验证摘要与正式回答的串联、纯文字兼容和持久化检查点。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.Session
import org.qingjian.speech.learning.ai.memory.Conversations
import org.qingjian.speech.learning.ai.tools.ToolSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SessionMemoryProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val conversations = Conversations(app); conversations.automatic = true
        val topic = conversations.create(0).topic
        for(index in 1..14) {
            val id = store.question(topic, "话题问题$index")
            store.answer(topic, id, "话题回答$index")
        }
        val word = store.addWord("control"); store.setMeaning(word, "控制")
        Credentials(app).save(Profile("https://session-memory.invalid/v1", "fake", "fake-key"))
        ToolSettings.save(app, false)
        val bodies = mutableListOf<JSONObject>()
        val session = Session(app, 0, topic) {
            Client { url -> object : HttpURLConnection(url) {
                val body = ByteArrayOutputStream()
                var response = ""
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = body
                override fun getResponseCode(): Int {
                    bodies += JSONObject(body.toString("UTF-8"))
                    response = if(bodies.size == 1) "较早对话摘要" else "正式回答 control"
                    return 200
                }
                override fun getInputStream() = ByteArrayInputStream(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", response)))).toString().toByteArray())
            } }
        }
        val done = CountDownLatch(1)
        test.runOnMainSync { session.changed = { if(!session.busy) done.countDown() }; session.send("control 怎么使用") }
        check(done.await(10, TimeUnit.SECONDS))
        check(bodies.size == 2 && bodies.none { it.has("tools") })
        check(bodies.last().getJSONArray("messages").toString().contains("较早对话摘要"))
        check(bodies.last().getJSONArray("messages").toString().contains("matching_words"))
        check(store.messages(topic).last().text == "正式回答 control")
        check(conversations.get(topic)?.summary == "较早对话摘要")
        check(store.messages("general").isEmpty())
        test.runOnMainSync { session.close() }
        Credentials(app).clear(); ToolSettings.save(app, true); store.clearLearning()
        return "PASS integrated send: one summary plus one answer, no tools on plain relay, scoped related data and durable summary after successful answer"
    }
}
