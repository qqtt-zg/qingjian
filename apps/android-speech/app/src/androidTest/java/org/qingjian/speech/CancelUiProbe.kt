//! 隔离设备验证部分工具已准备后取消，不会在重建时自动补播排队的声音。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.ActionStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object CancelUiProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext; val ui = ProbeUi(test)
        val store = LearningStore.get(app); store.clearLearning()
        val word = store.addWord("control"); store.setMeaning(word, "控制")
        val calls = AtomicInteger(); val waiting = CountDownLatch(1); val release = CountDownLatch(1)
        val tool = JSONObject().put("id", "queued-speech").put("type", "function").put("function", JSONObject()
            .put("name", "request_pronunciation").put("arguments", JSONObject().put("word_id", word).toString()))
        val first = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(tool))))).toString()
        URL.setURLStreamHandlerFactory { scheme -> if(scheme != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                check(url.host == "cancel-ui.invalid")
                var step = 0
                return object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() { if(step == 2) release.countDown() }
                    override fun usingProxy() = false
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getResponseCode(): Int {
                        step = calls.incrementAndGet()
                        if(step == 2) { waiting.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                        return 200
                    }
                    override fun getInputStream() = ByteArrayInputStream((if(step == 1) first else """{"choices":[{"message":{"content":"朗读请求已准备。"}}]}""").toByteArray())
                }
            }
        } }
        Credentials(app).save(Profile("https://cancel-ui.invalid/v1", "fake", "fake-key"))
        org.qingjian.speech.learning.ai.tools.ToolSettings.save(app, true)
        var tutor = test.startActivitySync(Intent(app, TutorActivity::class.java).putExtra("home_tab", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TutorActivity
        ui.await("cancel page") { tutor.hasWindowFocus() }
        ui.main { tutor.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("请朗读 control") }
        ui.click(tutor, "发送问题")
        if(!waiting.await(10, TimeUnit.SECONDS)) {
            var notice = ""
            ui.main { notice = tutor.findViewById<android.widget.TextView>(TutorActivity.ID_STATUS).text.toString() }
            error("followup missing: calls=${calls.get()}, state=${store.messages("general").lastOrNull()?.state}, notice=$notice")
        }
        ui.click(tutor, "取消本次请求")
        ui.await("cancelled question") { store.messages("general").lastOrNull()?.state == "failed" }
        check(ActionStore(app).forTopic("general").single().state == "pending")
        check(store.word(word)?.played == 0)
        val monitor = test.addMonitor(TutorActivity::class.java.name, null, false)
        ui.main { tutor.recreate() }
        tutor = test.waitForMonitorWithTimeout(monitor, 15000) as? TutorActivity ?: error("cancel recreation failed")
        test.removeMonitor(monitor); ui.await("cancel restored") { tutor.hasWindowFocus() }
        check(ActionStore(app).forTopic("general").single().state == "pending" && store.word(word)?.played == 0)
        check(calls.get() == 2)
        ui.main { tutor.finish() }; Credentials(app).clear()
        return "PASS cancel after queued pronunciation: no background dispatch, no replay on recreation, no completed-play count and no automatic request retry"
    }
}
