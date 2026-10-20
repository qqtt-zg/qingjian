//! 隔离设备验证工具白名单、参数边界、待确认操作与重复执行保护。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.ModelReply
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.ToolCall
import org.qingjian.speech.learning.ai.tools.ActionStore
import org.qingjian.speech.learning.ai.tools.Catalog
import org.qingjian.speech.learning.ai.tools.Executor
import org.qingjian.speech.learning.ai.tools.Runner
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ToolsProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val actions = ActionStore(app); val executor = Executor(app); val practice = PracticeStore(app)
        val control = store.addWord("control"); store.setMeaning(control, "控制")
        val volume = store.addWord("volume"); store.setMeaning(volume, "音量")
        val question = store.question("general", "帮我学习")
        fun call(name: String, args: String) = executor.execute(ToolCall("call-test", name, args), "general", question)
        check(Catalog.definitions().length() == 5)
        val query = call("search_vocabulary", """{"query":"控制","limit":10}""")
        check(query.getJSONArray("words").getJSONObject(0).getLong("word_id") == control)
        check(call("search_vocabulary", """{"query":"%","limit":10}""").getJSONArray("words").length() == 0)
        check(call("search_vocabulary", """{"query":"","limit":999}""").getString("status") == "invalid_arguments")
        check(call("clear_learning", "{}").getString("status") == "denied" && store.words().size == 2)
        val data = """{"english":"help","meaning":"帮助","example":"Can you help me?","translation":"你能帮我吗？"}"""
        check(call("prepare_word_card", data.dropLast(1)+",\"state\":\"applied\"}").getString("status") == "invalid_arguments")
        val queued = call("prepare_word_card", data)
        val id = queued.getLong("action_id")
        check(queued.getString("status") == "pending_confirmation" && store.words("help").isEmpty())
        check(call("prepare_word_card", data).getLong("action_id") == id && actions.forTopic("general").size == 1)
        val help = actions.saveCard(id)
        check(actions.saveCard(id) == help && store.words("help").size == 1)
        check(actions.approvedCard(help)?.getString("example") == "Can you help me?")
        check(store.word(help)?.received == 0 && store.word(help)?.played == 0)
        val existing = call("prepare_word_card", data.replace("help", "control").replace("帮助", "不同的意思")).getLong("action_id")
        actions.saveCard(existing); check(store.word(control)?.meaning == "控制")
        val dismissed = call("prepare_word_card", data.replace("help", "ready")).getLong("action_id")
        actions.dismiss(dismissed)
        check(runCatching { actions.saveCard(dismissed) }.isFailure && store.words("ready").isEmpty())
        val exercise = call("prepare_practice", """{"word_ids":[$control,$volume],"kind":"CHOICE"}""").getLong("action_id")
        check(practice.summary(Kind.CHOICE).first == 0)
        val round = actions.practice(exercise)
        check(round.size == 2 && actions.practice(exercise).map { it.key } == round.map { it.key })
        practice.submit(round.first(), round.first().meaning); practice.submit(round.first(), round.first().meaning)
        check(practice.summary(Kind.CHOICE) == (1 to 1))
        practice.submit(round.last(), round.last().options.first { it != round.last().meaning })
        val overview = call("get_learning_overview", "{}")
        check(overview.getJSONArray("mistakes").length() == 1)
        check(overview.getJSONObject("quizzes").getJSONObject("CHOICE").getInt("attempts") == 2)
        val speech = call("request_pronunciation", """{"word_id":$control}""").getLong("action_id")
        check(actions.claimPronunciation(speech) == "control")
        check(runCatching { actions.claimPronunciation(speech) }.isFailure)
        check(call("request_pronunciation", """{"word_id":999999}""").getString("status") == "invalid_arguments")
        val reply = """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"one","type":"function","function":{"name":"get_learning_overview","arguments":"{}"}}]}}]}"""
        check(ModelReply.parse(reply).calls.single().name == "get_learning_overview")
        check(runCatching { Client.parse(reply) }.isFailure)
        check(runCatching { ModelReply.parse(reply.replace("\"message\":", "\"finish_reason\":\"length\",\"message\":")) }.isFailure)
        val requests = AtomicInteger()
        val cancelled = AtomicBoolean()
        fun client(cancel: Boolean = false) = Client { url -> object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode(): Int { requests.incrementAndGet(); if(cancel) cancelled.set(true); return 200 }
            override fun getInputStream() = ByteArrayInputStream(reply.toByteArray())
        } }
        val profile = Profile("https://tools.invalid/v1", "fake", "fake-key")
        check(runCatching { Runner(app).complete(client(), profile, JSONArray(), "general", question, { false }, {}) }.isFailure)
        check(requests.get() == 3)
        requests.set(0)
        check(runCatching { Runner(app).complete(client(true), profile, JSONArray(), "general", question, { cancelled.get() }, {}) }.isFailure)
        check(requests.get() == 1)
        store.clearMessages("general")
        val next = store.question("general", "新的问题")
        check(next > question && actions.forTopic("general").isEmpty())
        store.answer("general", question, "晚到的旧回答")
        check(store.messages("general").size == 1)
        check(runCatching { actions.queue("general", question, "prepare_word_card", JSONObject(data)) }.isFailure)
        check(actions.approvedCard(help) != null)
        org.qingjian.speech.learning.ai.tools.ToolSettings.save(app, false)
        val plainBody = ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        val session = org.qingjian.speech.learning.ai.Session(app, 0) {
            Client { url -> object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = plainBody
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream("""{"choices":[{"message":{"content":"普通文字回答"}}]}""".toByteArray())
            } }
        }
        org.qingjian.speech.learning.ai.Credentials(app).save(profile)
        test.runOnMainSync { session.changed = { if(!session.busy) done.countDown() }; session.send("普通文字问题") }
        check(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        check(!JSONObject(plainBody.toString("UTF-8")).has("tools"))
        test.runOnMainSync { session.close() }
        org.qingjian.speech.learning.ai.Credentials(app).clear()
        org.qingjian.speech.learning.ai.tools.ToolSettings.save(app, true)
        store.clearLearning()
        check(actions.forTopic("general").isEmpty() && actions.approvedCard(help) == null)
        return "PASS tools: five-function catalog, bounded literal search, unknown/invalid denied, no implicit writes, persistent dedup/save/dismiss, preserved notes, stable quiz IDs/no fake grade, one-shot pronunciation, tool-only parser, three-turn cap, cancellation and clear/late-result isolation"
    }
}
