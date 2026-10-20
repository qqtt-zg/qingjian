//! 隔离设备验证自动保存、内联英文题、真实朗读与试听不计分，以及旋转后幂等状态。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Button
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.ai.tools.ActionStore
import org.qingjian.speech.learning.ai.tools.ToolSettings
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Kind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object ToolUiProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext; val ui = ProbeUi(test)
        val store = LearningStore.get(app); store.clearLearning()
        val control = store.addWord("control"); store.setMeaning(control, "控制")
        val volume = store.addWord("volume"); store.setMeaning(volume, "音量")
        app.getSharedPreferences("speech", 0).edit().putString("pack", "builtin").putInt("voice", 7).commit()
        val calls = AtomicInteger(); val bodies = mutableListOf<JSONObject>()
        fun response(vararg tools: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", JSONObject.NULL).put("tool_calls", JSONArray(tools.toList()))))).toString()
        fun call(id: String, name: String, data: JSONObject) = JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", data.toString()))
        val responses = listOf(
            response(call("lookup", "search_vocabulary", JSONObject().put("query", "control").put("limit", 3))),
            response(call("card", "prepare_word_card", JSONObject().put("english", "help").put("meaning", "帮助")
                .put("example", "Can you help me?").put("translation", "你能帮我吗？")),
                call("quiz", "prepare_practice", JSONObject().put("word_ids", JSONArray(listOf(control))).put("kind", "ENGLISH_CHOICE")),
                call("speech", "request_pronunciation", JSONObject().put("word_id", control))),
            """{"choices":[{"message":{"content":"help 词卡已加入待学习，练习可以直接作答。朗读将提交，不代表播放完成。"}}]}""")
        URL.setURLStreamHandlerFactory { protocol -> if(protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                check(url.host == "tool-ui.invalid")
                val sent = ByteArrayOutputStream(); var step = 0
                return object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getOutputStream() = sent
                    override fun getResponseCode(): Int { bodies.add(JSONObject(sent.toString("UTF-8"))); step = calls.getAndIncrement(); return 200 }
                    override fun getInputStream() = ByteArrayInputStream(responses[step].toByteArray())
                }
            }
        } }
        Credentials(app).save(Profile("https://tool-ui.invalid/v1", "fake-model", "fake-key")); ToolSettings.save(app, true)
        var tutor = test.startActivitySync(Intent(app, TutorActivity::class.java).putExtra("home_tab", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TutorActivity
        ui.await("AI focused") { tutor.hasWindowFocus() }
        ui.main { tutor.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("查 control，整理 help 词卡，出一道英文选词练习，并朗读 control") }
        ui.click(tutor, "发送问题")
        ui.await("automatic word and inline quiz", 20000) { store.words("help").isNotEmpty() && ui.button(tutor.window.decorView, "control")?.isEnabled == true }
        check(calls.get() == 3 && bodies.first().getJSONArray("tools").length() == 5 && bodies.last().getString("tool_choice") == "none")
        check(ui.button(tutor.window.decorView, "保存这张词卡") == null && ui.button(tutor.window.decorView, "开始这轮练习") == null)
        ui.await("automatic pronunciation completes", 60000) { store.word(control)!!.played == 1 }
        check(PracticeStore(app).summary(Kind.ENGLISH_CHOICE).first == 0)
        val played = store.word(control)!!.played
        ui.main {
            val speaker = checkNotNull(ui.target(tutor.window.decorView, "朗读选项：control"))
            check(speaker.performClick() && speaker.isSelected)
            check(PracticeStore(app).summary(Kind.ENGLISH_CHOICE).first == 0)
        }
        ui.await("preview finishes", 60000) { ui.text(tutor.window.decorView).contains("朗读完成") }
        check(store.word(control)!!.played == played)
        ui.click(tutor, "control")
        ui.await("grade saved") { PracticeStore(app).summary(Kind.ENGLISH_CHOICE) == (1 to 1) }
        ui.main {
            check(ui.button(tutor.window.decorView, "control")?.isEnabled == false)
            check((ui.target(tutor.window.decorView, "朗读选项：volume") as Button).isEnabled)
        }
        ui.click(tutor, "朗读选项：volume")
        ui.await("other option finishes", 60000) { ui.text(tutor.window.decorView).contains("朗读完成") }
        check(PracticeStore(app).summary(Kind.ENGLISH_CHOICE) == (1 to 1))
        test.waitForIdleSync(); test.uiAutomation.waitForIdle(300, 5000)
        val optionsImage = checkNotNull(test.uiAutomation.takeScreenshot())
        File(app.filesDir, "ui-review").mkdirs()
        File(app.filesDir, "ui-review/english-options.png").outputStream().use { optionsImage.compress(Bitmap.CompressFormat.PNG, 100, it) }
        optionsImage.recycle()
        val monitor = test.addMonitor(TutorActivity::class.java.name, null, false)
        ui.main { tutor.recreate() }
        tutor = test.waitForMonitorWithTimeout(monitor, 15000) as? TutorActivity ?: error("AI recreation failed")
        test.removeMonitor(monitor); ui.await("restored") { tutor.hasWindowFocus() }
        check(calls.get() == 3 && store.word(control)!!.played == played)
        check(PracticeStore(app).summary(Kind.ENGLISH_CHOICE) == (1 to 1))
        ui.click(tutor, "撤销本次新增：help")
        check(store.words("help").isEmpty() && store.word(control) != null && store.word(volume) != null)
        ui.click(tutor, "查看本轮小结")
        ui.main { check(ui.text(tutor.window.decorView).contains("本轮完成")) }
        test.waitForIdleSync(); test.uiAutomation.waitForIdle(300, 5000)
        val screenshot = checkNotNull(test.uiAutomation.takeScreenshot())
        File(app.filesDir, "ui-review").mkdirs()
        File(app.filesDir, "ui-review/automatic-ai.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        app.stopService(Intent(app, ReceiverService::class.java))
        ui.main { tutor.finish() }; Credentials(app).clear()
        check(ActionStore(app).forTopic("general").any { it.state == "undone" })
        return "PASS automatic word/undo, inline English quiz without confirm, actual one-shot pronunciation, option preview separated from answer/counts, both options after grade, rotation no replay/regrade, round summary"
    }
}
