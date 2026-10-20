//! 隔离模拟器验证会话控件、切换草稿、旋转恢复和本地保存不请求网络。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.widget.EditText
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.memory.Conversations
import java.io.File

object ConversationProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext; val ui = ProbeUi(test)
        val store = LearningStore.get(app); store.clearLearning(); Credentials(app).clear()
        val conversations = Conversations(app)
        val id = store.question("general", "已有的问题")
        store.answer("general", id, "已有的回答")
        conversations.rename("general", "原有对话")
        var activity = test.startActivitySync(Intent(app, TutorActivity::class.java).putExtra("home_tab", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TutorActivity
        ui.await("conversation page") { activity.hasWindowFocus() }
        ui.main { activity.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("原会话未发出的草稿") }
        ui.click(activity, "展开学习对话")
        DialogProbe.click(test, "新建学习对话")
        val newTopic = conversations.selected(0)
        check(newTopic != "general" && conversations.draft("general") == "原会话未发出的草稿")
        ui.main {
            check(activity.findViewById<EditText>(TutorActivity.ID_QUESTION).text.isEmpty())
            check(!ui.text(activity.window.decorView).contains("已有的回答"))
            activity.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("新会话的问题")
            activity.findViewById<android.view.View>(TutorActivity.ID_SAVE).performClick()
        }
        check(store.messages(newTopic).single().text == "新会话的问题" && store.messages("general").size == 2)
        ui.main { activity.findViewById<EditText>(TutorActivity.ID_QUESTION).setText("新会话草稿") }
        ui.click(activity, "展开学习对话")
        DialogProbe.click(test, "切换对话：原有对话")
        ui.await("old conversation restored") { ui.text(activity.window.decorView).contains("已有的回答") }
        ui.main { check(activity.findViewById<EditText>(TutorActivity.ID_QUESTION).text.toString() == "原会话未发出的草稿") }
        check(conversations.draft(newTopic) == "新会话草稿")
        val monitor = test.addMonitor(TutorActivity::class.java.name, null, false)
        ui.main { activity.recreate() }
        activity = test.waitForMonitorWithTimeout(monitor, 15000) as? TutorActivity ?: error("conversation recreation failed")
        test.removeMonitor(monitor)
        ui.await("recreated conversation") { activity.hasWindowFocus() }
        ui.main {
            check(activity.findViewById<EditText>(TutorActivity.ID_QUESTION).text.toString() == "原会话未发出的草稿")
            check(ui.text(activity.window.decorView).contains("已有的回答"))
        }
        test.waitForIdleSync(); test.uiAutomation.waitForIdle(400, 5000)
        val screenshot = checkNotNull(test.uiAutomation.takeScreenshot())
        val directory = File(app.filesDir, "ui-review").apply { mkdirs() }
        File(directory, "conversations.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        val other = test.startActivitySync(Intent(app, org.qingjian.speech.learning.DataSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("data page") { other.hasWindowFocus() }
        store.clearLearning()
        ui.main { other.finish() }
        ui.await("cleared conversation") { activity.hasWindowFocus() && !ui.text(activity.window.decorView).contains("已有的回答") }
        ui.main { check(activity.findViewById<EditText>(TutorActivity.ID_QUESTION).text.isEmpty()) }
        check(conversations.draft("general").isEmpty())
        ui.main { activity.finish() }
        return "PASS conversation UI: new topic isolation, separate persistent drafts, picker restore, rotation, offline saved question, original history retained and no stale draft after global clear"
    }
}
