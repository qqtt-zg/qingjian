//! 实际答题结果进入下一次模型上下文，关闭工具时仍只发送文字请求。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.Session
import org.qingjian.speech.learning.ai.tools.ActionStore
import org.qingjian.speech.learning.ai.tools.ToolSettings
import org.qingjian.speech.learning.practice.PracticeStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object FollowupProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext; val store = LearningStore.get(app); store.clearLearning()
        val control = store.addWord("control"); store.setMeaning(control, "控制")
        val volume = store.addWord("volume"); store.setMeaning(volume, "音量")
        val id = store.question("general", "出一道英文选词练习")
        val actions = ActionStore(app)
        val action = actions.queue("general", id, "prepare_practice", JSONObject().put("word_ids", JSONArray(listOf(control))).put("kind", "ENGLISH_CHOICE"))
        actions.markAutomatic(action.id)
        val q = actions.practice(action.id).single()
        PracticeStore(app).submit(q, "control"); store.answer("general", id, "题目已准备")
        val body = ByteArrayOutputStream()
        Credentials(app).save(Profile("https://followup.invalid/v1", "fake", "fake-key")); ToolSettings.save(app, false)
        val done = CountDownLatch(1)
        val session = Session(app, 0) { Client { url -> object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = body
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream("""{"choices":[{"message":{"content":"你刚才的本题答对了，可以继续练一句搭配。"}}]}""".toByteArray())
        } } }
        test.runOnMainSync { session.changed = { if(!session.busy) done.countDown() }; session.send("下一步可以学什么？") }
        check(done.await(10, TimeUnit.SECONDS))
        val sent = JSONObject(body.toString("UTF-8"))
        check(!sent.has("tools") && sent.getJSONArray("messages").toString().contains("practice_results"))
        val messages = sent.getJSONArray("messages")
        val content = (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).optString("content") }
        check(content.contains("\"correct\":true"))
        check(session.contextDescription.contains("实际作答：1 题，答对 1 题"))
        test.runOnMainSync { session.close() }; Credentials(app).clear(); ToolSettings.save(app, true)
        return "PASS followup context carries actual submitted results, bounded to recent actions, no fabricated grade and tool-disabled request stays plain text"
    }
}
