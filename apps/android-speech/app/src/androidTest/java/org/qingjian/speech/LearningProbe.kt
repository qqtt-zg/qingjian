//! 隔离设备上的学习链路验证；使用假密钥与受控 HTTP 连接，不访问真实 AI 服务。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.Session
import org.qingjian.speech.learning.ai.SettingsActivity
import org.qingjian.speech.learning.ai.Teaching
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object LearningProbe {
    fun run(test: Instrumentation): String {
        val context = test.targetContext
        val store = LearningStore.get(context)
        store.clearLearning()
        val id = store.addWord("Control")
        check(store.word(id)!!.received == 0)
        store.record("control"); store.record(" control ", true)
        store.setMeaning(id, "控制；管理"); store.reviewed(id)
        check(store.words("控制").single().id == id)
        check(store.words("%").isEmpty())
        val word = store.word(id)!!
        check(word.played == 1 && word.received == 1 && word.reviewed == 1)
        val question = store.question("word:$id", "怎么用？")
        check(store.messages("general").isEmpty())
        store.failed(question)
        check(store.messages("word:$id").single().state == "failed")
        store.answer("word:$id", question, "例句回答")
        check(store.messages("word:$id").size == 2)
        val prompt = Teaching.messages(word, store.messages("word:$id"), "请举例").toString()
        check(prompt.contains("控制") && prompt.contains("没有用户录音"))
        check(!prompt.contains("pairing") && !prompt.contains("learning_ai"))
        val credentials = Credentials(context)
        val profile = Profile("https://example.com/api/v1/", "test-model", "fake-learning-key-only")
        credentials.save(profile)
        check(credentials.load().key == profile.key)
        val prefs = context.getSharedPreferences("learning_ai", Context.MODE_PRIVATE)
        check(!prefs.all.values.toString().contains(profile.key))
        check(!profile.toString().contains(profile.key))
        check(profile.endpoint() == "https://example.com/api/v1/chat/completions")
        check(Profile("https://example.com/v1/chat/completions", "x", "x").endpoint().count { it == '/' } == 5)
        for(address in listOf("http://example.com/v1", "https://user:pass@example.com", "https://example.com/#key", "https://example.com/?key=x")) {
            check(runCatching { Profile(address, "x", "x").validate() }.isFailure)
        }
        val response = """{"choices":[{"message":{"content":"连接成功"},"finish_reason":"stop"}]}"""
        check(Client.parse(response) == "连接成功")
        check(Client.parse("""{"choices":[{"message":{"content":[{"type":"text","text":"数组回答"}]},"finish_reason":"length"}]}""").contains("长度上限"))
        check(runCatching { Client.parse("""{"choices":[{"message":{"content":null}}]}""") }.isFailure)
        val sent = ByteArrayOutputStream()
        var status = 200
        var redirectAllowed = true
        var bearer = ""
        val client = Client { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() { redirectAllowed = instanceFollowRedirects }
                override fun usingProxy() = false
                override fun getOutputStream() = sent
                override fun getResponseCode(): Int { bearer = getRequestProperty("Authorization"); return status }
                override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
            }
        }
        val messages = Teaching.messages(word, emptyList(), "怎么用")
        check(client.complete(profile, messages) == "连接成功")
        check(!redirectAllowed && bearer == "Bearer ${profile.key}")
        val payload = JSONObject(sent.toString("UTF-8"))
        check(payload.getString("model") == "test-model" && !payload.getBoolean("stream"))
        check(!payload.toString().contains(profile.key))
        status = 307
        check(runCatching { client.complete(profile, messages) }.exceptionOrNull()?.message?.contains("跳转") == true)
        status = 429
        check(runCatching { client.complete(profile, messages) }.exceptionOrNull()?.message?.contains("429") == true)
        client.cancel()
        check(runCatching { client.complete(profile, messages) }.isFailure)
        var calls = 0
        val session = Session(context, id) {
            Client { url -> object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getResponseCode(): Int { calls++; return 200 }
                override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
            } }
        }
        val answered = CountDownLatch(1)
        test.runOnMainSync {
            session.changed = { if(!session.busy) answered.countDown() }
            session.send("异步问题")
            session.send("忙碌期间不重复发送")
        }
        check(answered.await(10, TimeUnit.SECONDS))
        check(calls == 1 && store.messages("word:$id").last().text == "连接成功")
        test.runOnMainSync { session.close() }
        store.clearMessages("word:$id")
        store.answer("word:$id", question, "清空后的晚到回答")
        check(store.messages("word:$id").isEmpty())
        credentials.clear()
        check(credentials.load().key.isEmpty())

        val settings = test.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        test.runOnMainSync {
            check(settings.findViewById<EditText>(SettingsActivity.ID_KEY).isSaveEnabled.not())
            settings.finish()
        }
        val home = test.startActivitySync(Intent(context, LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as LearningActivity
        test.runOnMainSync {
            click(home.window.decorView, "词汇")
            check(text(home.window.decorView).contains("Control"))
            click(home.window.decorView, "复习")
            click(home.window.decorView, "开始复习")
            click(home.window.decorView, "查看笔记")
            click(home.window.decorView, "这次记住了")
            check(text(home.window.decorView).contains("本轮完成"))
        }
        val speechMonitor = test.addMonitor(MainActivity::class.java.name, null, false)
        test.runOnMainSync { click(home.window.decorView, "设置") }
        val speech = test.waitForMonitorWithTimeout(speechMonitor, 10000) as? MainActivity
            ?: error("设置标签没有打开统一设置页")
        test.removeMonitor(speechMonitor)
        test.runOnMainSync {
            check(speech.findViewById<View>(R.id.btnScan) != null)
            check(text(speech.window.decorView).contains("最近朗读"))
            check(text(speech.window.decorView).contains("词汇"))
            check(text(speech.window.decorView).contains("配置与测试 AI"))
            check(text(speech.window.decorView).contains("应用更新"))
            check(!text(speech.window.decorView).contains("打开朗读设置"))
        }
        test.runOnMainSync { click(speech.window.decorView, "复习") }
        test.waitForIdleSync()
        test.runOnMainSync {
            check(text(home.window.decorView).contains("本轮完成"))
            home.finish()
        }
        val tutor = test.startActivitySync(Intent(context, TutorActivity::class.java).putExtra("word_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TutorActivity
        test.runOnMainSync {
            tutor.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("下班后保存的问题")
            tutor.findViewById<Button>(TutorActivity.ID_SAVE).performClick()
            check(store.messages("word:$id").single().text == "下班后保存的问题")
            tutor.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("未配置 API 的问题")
            tutor.findViewById<Button>(TutorActivity.ID_SEND).performClick()
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        while(store.messages("word:$id").last().state != "failed" && System.nanoTime() < deadline) Thread.sleep(50)
        check(store.messages("word:$id").last().state == "failed")
        test.runOnMainSync { tutor.finish() }
        return "PASS SQLite counts/search/review/chat isolation, encrypted credentials, HTTPS validation, Chat Completions payload/parser/redirect/429/cancel, four-tab unified settings/return/review retention and offline questions"
    }

    private fun click(view: View, label: String) {
        if(view is Button && view.text.toString() == label) { check(view.performClick()); return }
        if(view is ViewGroup) for(index in 0 until view.childCount) {
            if(text(view.getChildAt(index)).contains(label)) {
                runCatching { click(view.getChildAt(index), label) }.onSuccess { return }
            }
        }
        error("找不到按钮：$label")
    }

    private fun text(view: View): String = when(view) {
        is android.widget.TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }
}
