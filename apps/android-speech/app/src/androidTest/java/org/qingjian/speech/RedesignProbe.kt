//! 隔离模拟器验证新导航、真实词卡、学习偏好及无自动请求的 AI 专属入口。
package org.qingjian.speech

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.widget.EditText
import android.widget.Spinner
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.PreferencesActivity
import org.qingjian.speech.learning.SettingsHomeActivity
import org.qingjian.speech.learning.StudyPreferences
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.QuizActivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object RedesignProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val ui = ProbeUi(test)
        val store = LearningStore.get(app)
        store.clearLearning()
        val calls = AtomicInteger()
        var payload = ByteArrayOutputStream()
        URL.setURLStreamHandlerFactory { protocol -> if(protocol != "https") null else object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                check(url.host == "redesign.invalid")
                payload = ByteArrayOutputStream()
                return object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getOutputStream() = payload
                    override fun getResponseCode(): Int { calls.incrementAndGet(); return 200 }
                    override fun getInputStream() = ByteArrayInputStream("""{"choices":[{"message":{"content":"先学 help：帮助。Can you help me? 你能帮我吗？\n我们先学这一个词，再练习一句话。"}}]}""".toByteArray())
                }
            }
        } }
        fun launch(type: Class<out Activity>, word: Long = 0) = test.startActivitySync(
            Intent(app, type).putExtra("word_id", word).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun open(from: Activity, label: String, type: Class<out Activity>): Activity {
            val monitor = test.addMonitor(type.name, null, false)
            ui.click(from, label)
            val result = test.waitForMonitorWithTimeout(monitor, 15000) ?: error("没有打开 ${type.simpleName}")
            test.removeMonitor(monitor); ui.await("${type.simpleName} focus") { result.hasWindowFocus() }
            return result
        }
        fun capture(name: String) {
            test.waitForIdleSync()
            test.uiAutomation.waitForIdle(400, 5000)
            val image = checkNotNull(test.uiAutomation.takeScreenshot())
            val directory = File(app.filesDir, "ui-review").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        var home = launch(LearningActivity::class.java)
        ui.await("empty home") { home.hasWindowFocus() }
        ui.main {
            check(ui.text(home.window.decorView).contains("安排今天学习"))
            check(ui.button(home.window.decorView, "AI 老师") != null)
            check(ui.button(home.window.decorView, "复习") == null)
            check(ui.button(home.window.decorView, "设置") == null)
        }
        capture("empty-home")
        ui.main { home.finish() }
        val control = store.addWord("control"); store.setMeaning(control, "控制；调节")
        val volume = store.addWord("volume"); store.setMeaning(volume, "音量")
        StudyPreferences(app).lastWord = control
        val question = store.question("word:$control", "给我一个简单例句")
        store.answer("word:$control", question, "I can control the volume.\n我可以调节音量。\n\ncontrol the volume：调节音量。先记住这一个常用搭配。")
        home = launch(LearningActivity::class.java)
        ui.await("home") { home.hasWindowFocus() }; capture("home")
        val intro = open(home, "AI 老师", TutorActivity::class.java)
        capture("ai-home")
        ui.main { intro.finish() }
        ui.await("intro returned") { home.hasWindowFocus() }
        val detail = open(home, "学习这个词：control", WordStudyActivity::class.java)
        ui.main { check(ui.text(detail.window.decorView).contains("I can control the volume.")) }
        capture("word")
        ui.click(detail, "相近词怎么选？")
        ui.main {
            detail.findViewById<EditText>(WordStudyActivity.ID_OTHER).setText("manage")
            detail.findViewById<EditText>(WordStudyActivity.ID_CONTEXT).setText("管理团队")
        }
        val tutor = open(detail, "准备辨析问题", TutorActivity::class.java)
        ui.main {
            check(tutor.findViewById<EditText>(TutorActivity.ID_QUESTION).text.contains("manage"))
            check(calls.get() == 0)
            tutor.finish()
        }
        ui.await("return word") { detail.hasWindowFocus() }
        ui.main {
            check(detail.findViewById<EditText>(WordStudyActivity.ID_OTHER).text.toString() == "manage")
            ToolbarProbe.back(detail)
        }
        ui.await("return home") { home.hasWindowFocus() }
        val settings = open(home, "设置", SettingsHomeActivity::class.java)
        capture("settings")
        val preferences = open(settings, "学习偏好", PreferencesActivity::class.java)
        ui.main {
            fun find(view: android.view.View): Spinner? {
                if(view is Spinner && view.contentDescription == "每天期望的新词") return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            checkNotNull(find(preferences.window.decorView)).setSelection(0)
        }
        ui.click(preferences, "保存学习偏好")
        ui.await("preferences saved") { settings.hasWindowFocus() && StudyPreferences(app).newWords == 3 }
        ui.main { ToolbarProbe.back(settings) }
        ui.await("home after settings") { home.hasWindowFocus() }
        val ai = open(home, "AI 老师", TutorActivity::class.java)
        ui.main {
            check(ui.text(ai.window.decorView).contains("有不懂的"))
            check(calls.get() == 0)
        }
        val keyboard = ai.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        ui.main {
            ai.findViewById<EditText>(TutorActivity.ID_QUESTION).requestFocus()
            keyboard.showSoftInput(ai.findViewById(TutorActivity.ID_QUESTION), android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        ui.await("keyboard visible") { ai.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
        ui.main {
            val bounds = android.graphics.Rect()
            check(ai.findViewById<android.view.View>(TutorActivity.ID_SEND).getGlobalVisibleRect(bounds))
            val keyboardTop = ai.window.decorView.height - ai.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            check(bounds.bottom <= keyboardTop) { "发送按钮被键盘遮挡：${bounds.bottom} > $keyboardTop" }
        }
        capture("ai-keyboard")
        ui.main { keyboard.hideSoftInputFromWindow(ai.findViewById<EditText>(TutorActivity.ID_QUESTION).windowToken, 0) }
        ui.await("keyboard hidden") { !ai.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
        ui.click(ai, "今天学哪几个词？")
        ui.main {
            check(ai.findViewById<EditText>(TutorActivity.ID_QUESTION).text.contains("3 个"))
            check(calls.get() == 0)
        }
        Credentials(app).save(Profile("https://redesign.invalid/v1", "fake-model", "fake-key"))
        ui.click(ai, "发送问题")
        ui.await("AI saved response") { store.messages("general").lastOrNull()?.role == "assistant" }
        ui.await("AI response visible") { ui.text(ai.window.decorView).contains("Can you help me?") }
        check(calls.get() == 1 && payload.toString("UTF-8").contains("期望每天新词"))
        capture("ai-chat")
        ui.main { ai.finish(); home.finish() }
        val quiz = test.startActivitySync(Intent(app, QuizActivity::class.java).putExtra("word_id", control)
            .putExtra("kind", Kind.CHOICE.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("focused quiz") { quiz.hasWindowFocus() }
        ui.main {
            check(ui.text(quiz.window.decorView).contains("1 / 1"))
            check(ui.text(quiz.window.decorView).contains("control")); quiz.finish()
        }
        check(store.word(control)?.meaning == "控制；调节")
        check(store.messages("word:$control").last().text.contains("control the volume"))
        var review = test.startActivitySync(Intent(app, LearningActivity::class.java).putExtra("page", "review").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("review focus") { review.hasWindowFocus() }
        ui.click(review, "开始复习"); ui.click(review, "查看笔记")
        val monitor = test.addMonitor(LearningActivity::class.java.name, null, false)
        val previousCount = store.words().sumOf { it.reviewed }
        ui.main { review.recreate() }
        review = test.waitForMonitorWithTimeout(monitor, 15000) ?: error("review recreation failed")
        test.removeMonitor(monitor); ui.await("review restored") { review.hasWindowFocus() }
        ui.main {
            check(ui.button(review.window.decorView, "这次记住了") != null)
            check(store.words().sumOf { it.reviewed } == previousCount)
            review.finish()
        }
        Credentials(app).clear()
        return "PASS redesigned UI: empty/home/word/AI/settings screenshots, three destinations, contextual draft without request, return drafts, persisted preference in explicit API payload, saved answer, focused quiz, keyboard-visible send action and preserved learning records"
    }
}
