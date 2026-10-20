//! 隔离设备验证上下文压缩、字面检索、取消和清空期间的摘要修订保护。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.ChatEntry
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.Teaching
import org.qingjian.speech.learning.ai.memory.Conversations
import org.qingjian.speech.learning.ai.memory.History
import org.qingjian.speech.learning.ai.memory.Memory
import org.qingjian.speech.learning.ai.memory.Retrieval
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.Question
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object MemoryProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val conversations = Conversations(app); conversations.automatic = true
        val a = conversations.create(0); val b = conversations.create(0)
        for(index in 1..14) {
            val id = store.question(a.topic, "旧问题$index control")
            store.answer(a.topic, id, "旧回答$index：control 表示控制。")
        }
        store.question(b.topic, "另一个独立话题")
        val id = store.question(a.topic, "当前新问题")
        val sent = mutableListOf<JSONObject>()
        var status = 200
        var action: () -> Unit = {}
        val client = Client { url -> object : HttpURLConnection(url) {
            val output = ByteArrayOutputStream()
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = output
            override fun getResponseCode(): Int { sent += JSONObject(output.toString("UTF-8")); action(); return status }
            override fun getInputStream() = ByteArrayInputStream("""{"choices":[{"message":{"content":"已讨论 control；用户还想了解 manage 的区别。"}}]}""".toByteArray())
        } }
        val profile = Profile("https://memory.invalid/v1", "fake", "fake-key")
        val memory = Memory(conversations, checkNotNull(conversations.get(a.topic)), id)
        val summary = memory.prepare(client, profile, { false }, {})
        check(sent.size == 1 && !sent.single().has("tools"))
        check(sent.single().getJSONArray("messages").toString().contains("旧问题1"))
        check(!sent.single().toString().contains("另一个独立话题"))
        val prompt = Teaching.messages(null, memory.history(), "当前新问题", summary = summary)
        check(prompt.toString().contains("旧问题14") && !prompt.toString().contains("旧问题1 control"))
        memory.save()
        val saved = checkNotNull(conversations.get(a.topic))
        check(saved.summary == summary && saved.through > 0 && store.messages(a.topic).size == 29)
        check(conversations.get(b.topic)?.summary == "")
        val second = Memory(conversations, saved, id)
        check(second.prepare(client, profile, { false }, {}) == summary && sent.size == 1)
        val stale = saved
        conversations.forgetSummary(a.topic)
        check(!conversations.saveSummary(stale, stale.through + 1, "晚到摘要", id))
        val failed = Memory(conversations, checkNotNull(conversations.get(a.topic)), id)
        status = 401
        check(failed.prepare(client, profile, { false }, {}).isEmpty() && failed.fallback)
        failed.save(); check(conversations.get(a.topic)?.summary == "")
        status = 200
        val cancelled = AtomicBoolean(false)
        action = { cancelled.set(true) }
        val stopped = Memory(conversations, checkNotNull(conversations.get(a.topic)), id)
        check(runCatching { stopped.prepare(client, profile, { cancelled.get() }, {}) }.exceptionOrNull() is CancellationException)
        stopped.save(); check(conversations.get(a.topic)?.summary == "")
        action = {}
        val clear = Memory(conversations, checkNotNull(conversations.get(a.topic)), id)
        clear.prepare(client, profile, { false }, {})
        store.clearMessages(a.topic); clear.save()
        check(conversations.get(a.topic)?.summary == "" && store.messages(a.topic).isEmpty())
        val long = listOf(ChatEntry(1, "user", "怎么使用 control", "done"), ChatEntry(2, "assistant", "x".repeat(30000), "done"))
        val packed = History.recent(long)
        check(packed.size == 2 && packed.sumOf { it.text.length } <= 16000 && packed.last().text.contains("截断"))
        check(History.recent(long + ChatEntry(3, "user", "失败的问题", "failed")).none { it.id == 3L })
        val control = store.addWord("control"); store.setMeaning(control, "控制")
        val unrelated = store.addWord("banana"); store.setMeaning(unrelated, "香蕉")
        PracticeStore(app).submit(Question("memory-quiz", control, "control", "控制", Kind.SPELLING, emptyList()), "controll")
        val related = Retrieval(app).find("How do I use control?")
        check(related.getJSONArray("matching_words").length() == 1 && related.getJSONArray("recent_mistakes").length() == 1)
        check(!related.toString().contains("banana"))
        check(Retrieval(app).find("%_").getJSONArray("matching_words").length() == 0)
        check(Retrieval(app).find("复习错题").getJSONArray("recent_mistakes").length() == 1)
        val wordConversation = conversations.create(control)
        val wordQuestion = store.question(wordConversation.topic, "另一段单词学习")
        store.answer(wordConversation.topic, wordQuestion, "这个词的新讲解")
        check(conversations.get(wordConversation.topic)?.wordId == control)
        check(conversations.latestAnswer(control)?.first == wordConversation.topic)
        check(conversations.latestAnswer(control)?.second?.text == "这个词的新讲解")
        val waiting = store.question(wordConversation.topic, "很早保存但还未发送的问题")
        store.failed(waiting)
        val later = store.question(wordConversation.topic, "之后的问题")
        store.answer(wordConversation.topic, later, "之后的回答")
        store.answer(wordConversation.topic, waiting, "很早问题的晚到回答")
        val retryBlock = History.blocks(conversations.recent(wordConversation.topic, later, Long.MAX_VALUE)).last()
        check(retryBlock.first().id == waiting && retryBlock.last().replyTo == waiting)
        check(!store.answer(wordConversation.topic, waiting, "重复的回答"))
        store.failed(waiting)
        check(store.messages(wordConversation.topic).first { it.id == waiting }.state == "done")
        conversations.automatic = false
        val disabled = Memory(conversations, checkNotNull(conversations.get(b.topic)), Long.MAX_VALUE)
        val count = sent.size
        check(disabled.prepare(client, profile, { false }, {}).isEmpty() && sent.size == count)
        conversations.saveDraft(b.topic, "本地草稿")
        store.clearLearning(); check(conversations.list(0).isEmpty() && conversations.draft(b.topic).isEmpty())
        conversations.automatic = true
        return "PASS memory: independent topics, bounded extra tool-free summary, old originals retained, checkpoint reuse, failure fallback, cancel/clear/revision guards, long paired history clipping, relevant real words/mistakes and clear-all drafts"
    }
}
