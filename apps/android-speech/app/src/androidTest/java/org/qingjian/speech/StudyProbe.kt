//! 隔离模拟器验证引导学习、草稿确认、单次显式请求和离线讲解回看。
package org.qingjian.speech

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toolbar
import org.json.JSONObject
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.StudyDirection
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.atomic.AtomicInteger

object StudyProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app)
        store.clearLearning()
        val id = store.addWord("realize")
        store.setMeaning(id, "意识到；实现")
        val credentials = Credentials(app)
        credentials.save(Profile("https://study-probe.invalid/v1", "probe", "fake-study-key"))
        val calls = AtomicInteger()
        var sent = ByteArrayOutputStream()
        URL.setURLStreamHandlerFactory { protocol ->
            if(protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host == "study-probe.invalid")
                    sent = ByteArrayOutputStream()
                    return object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getOutputStream() = sent
                        override fun getResponseCode(): Int { calls.incrementAndGet(); return 200 }
                        override fun getInputStream() = ByteArrayInputStream("""{"choices":[{"message":{"content":"realize 可以表示意识到；这里是一条已保存的讲解。"}}]}""".toByteArray())
                    }
                }
            }
        }
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { block() } catch(error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun await(label: String, condition: () -> Boolean) {
            val end = System.nanoTime() + 10_000_000_000L
            while(System.nanoTime() < end) {
                var done = false; main { done = condition() }
                if(done) return
                Thread.sleep(30)
            }
            error("timed out: $label")
        }
        fun button(view: View, label: String): Button? {
            if(view is Button && view.text.toString() == label) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) button(view.getChildAt(i), label)?.let { return it }
            return null
        }
        fun text(view: View): String = when(view) {
            is TextView -> view.text.toString()
            is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
            else -> ""
        }
        fun click(activity: Activity, label: String) = main { checkNotNull(button(activity.window.decorView, label)).performClick() }
        fun openTutor(detail: WordStudyActivity, label: String): TutorActivity {
            val monitor = test.addMonitor(TutorActivity::class.java.name, null, false)
            click(detail, label)
            val tutor = test.waitForMonitorWithTimeout(monitor, 10000) as? TutorActivity ?: error("Tutor did not open")
            test.removeMonitor(monitor)
            await("tutor visible") { tutor.hasWindowFocus() }
            return tutor
        }
        for(direction in StudyDirection.entries.filter { it != StudyDirection.COMPARE }) {
            val question = direction.question("realize")
            check(question.contains("realize") && question.length <= 4000)
        }
        check(runCatching { StudyDirection.COMPARE.question("realize") }.isFailure)
        check(runCatching { StudyDirection.COMPARE.question("realize", "implement", "x".repeat(1001)) }.isFailure)
        val home = test.startActivitySync(Intent(app, LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        click(home, "词汇")
        val monitor = test.addMonitor(WordStudyActivity::class.java.name, null, false)
        click(home, "学习这个词")
        var detail = test.waitForMonitorWithTimeout(monitor, 10000) as? WordStudyActivity ?: error("Word study did not open")
        test.removeMonitor(monitor)
        await("detail visible") { detail.hasWindowFocus() }
        main {
            check(detail.findViewById<Toolbar>(R.id.pageToolbar).navigationIcon != null)
            check(text(detail.window.decorView).contains("还没有讲解"))
        }
        val tutor = openTutor(detail, "词性与用法")
        main {
            check(tutor.findViewById<EditText>(TutorActivity.ID_QUESTION).text.toString().contains("核心含义"))
            check(calls.get() == 0 && store.messages("word:$id").isEmpty())
            tutor.findViewById<Button>(TutorActivity.ID_SEND).performClick()
        }
        await("answer persisted") { store.messages("word:$id").lastOrNull()?.role == "assistant" }
        check(calls.get() == 1)
        check(JSONObject(sent.toString("UTF-8")).toString().contains("意识到；实现"))
        main { tutor.finish() }
        await("return detail") { detail.hasWindowFocus() && text(detail.window.decorView).contains("这里是一条已保存的讲解") }
        main {
            detail.findViewById<EditText>(WordStudyActivity.ID_OTHER).setText("implement")
            detail.findViewById<EditText>(WordStudyActivity.ID_CONTEXT).setText("我想表达实现一项计划")
        }
        val compare = openTutor(detail, "准备辨析问题")
        main {
            val draft = compare.findViewById<EditText>(TutorActivity.ID_QUESTION).text.toString()
            check(draft.contains("implement") && draft.contains("实现一项计划") && draft.contains("互换"))
            check(calls.get() == 1)
            compare.findViewById<Button>(TutorActivity.ID_SAVE).performClick()
            check(store.messages("word:$id").last().state == "pending")
            compare.finish()
        }
        await("comparison preserved") { detail.hasWindowFocus() && detail.findViewById<EditText>(WordStudyActivity.ID_OTHER).text.toString() == "implement" }
        // 新建页面模拟退出后重开；未配置 API 也能回看本机讲解。
        main { detail.finish(); home.finish() }
        credentials.clear()
        detail = test.startActivitySync(Intent(app, WordStudyActivity::class.java).putExtra("word_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WordStudyActivity
        await("offline detail") { detail.hasWindowFocus() }
        main { check(text(detail.window.decorView).contains("这里是一条已保存的讲解")) }
        val offline = openTutor(detail, "离线查看完整讲解")
        main {
            check(text(offline.window.decorView).contains("这里是一条已保存的讲解"))
            check(calls.get() == 1)
            offline.finish(); detail.finish()
        }
        return "PASS word learning: vocabulary entry, guided draft without auto-request, explicit send, per-word persisted answer, contextual comparison/offline question, return drafts, offline history"
    }
}
