//! 隔离模拟器内验证真实设置页的等待、成功、失败、取消；只使用虚构凭证和受控响应。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.SettingsActivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AiConnectionProbe {
    fun run(test: Instrumentation): String {
        var gate = CountDownLatch(1)
        var entered = CountDownLatch(1)
        val code = AtomicInteger(200)
        val requests = AtomicInteger()
        URL.setURLStreamHandlerFactory { protocol ->
            if(protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL): URLConnection {
                    check(url.host == "ai-probe.invalid")
                    val requestGate = gate
                    return object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() { requestGate.countDown() }
                        override fun usingProxy() = false
                        override fun getOutputStream(): ByteArrayOutputStream {
                            requests.incrementAndGet(); entered.countDown()
                            check(requestGate.await(15, TimeUnit.SECONDS)) { "probe gate timed out" }
                            return ByteArrayOutputStream()
                        }
                        override fun getResponseCode() = code.get()
                        override fun getInputStream() = ByteArrayInputStream(
                            """{"choices":[{"message":{"content":"连接成功"},"finish_reason":"stop"}]}""".toByteArray())
                    }
                }
            }
        }
        val app = test.targetContext
        Credentials(app).clear()
        val activity = test.startActivitySync(Intent(app, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun <T : View> view(id: Int): T = activity.findViewById(id)
        fun text(id: Int) = view<TextView>(id).text.toString()
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            test.runOnMainSync { try { block() } catch(error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while(System.nanoTime() < deadline) {
                var done = false
                main { done = condition() }
                if(done) return
                Thread.sleep(30)
            }
            error("timed out: $label")
        }
        fun saveButton(root: View): Button? {
            if(root is Button && root.text.toString() == "保存配置") return root
            if(root is ViewGroup) for(i in 0 until root.childCount) saveButton(root.getChildAt(i))?.let { return it }
            return null
        }
        try {
            main {
                view<EditText>(SettingsActivity.ID_ADDRESS).setText("https://ai-probe.invalid/v1")
                view<EditText>(SettingsActivity.ID_MODEL).setText("probe")
                view<EditText>(SettingsActivity.ID_KEY).setText("fake-ui-key")
                view<Button>(SettingsActivity.ID_TEST).performClick()
            }
            check(entered.await(5, TimeUnit.SECONDS))
            main {
                check(text(SettingsActivity.ID_RESULT) == "正在测试连接")
                check(view<View>(SettingsActivity.ID_PROGRESS).visibility == View.VISIBLE)
                check(!view<View>(SettingsActivity.ID_TEST).isEnabled)
                check(!view<View>(SettingsActivity.ID_CLEAR).isEnabled)
                check(!view<View>(SettingsActivity.ID_KEY).isEnabled)
                check(!checkNotNull(saveButton(activity.window.decorView)).isEnabled)
                check(view<View>(SettingsActivity.ID_CANCEL).visibility == View.VISIBLE)
            }
            await("elapsed time") { text(SettingsActivity.ID_ELAPSED).startsWith("已等待 1") }
            gate.countDown()
            await("success") { text(SettingsActivity.ID_RESULT) == "测试成功" }
            await("visible success") { view<View>(SettingsActivity.ID_RESULT).getGlobalVisibleRect(Rect()) }
            main {
                check(view<View>(SettingsActivity.ID_PROGRESS).visibility == View.GONE)
                check(view<View>(SettingsActivity.ID_TEST).isEnabled && view<View>(SettingsActivity.ID_KEY).isEnabled)
                check(text(SettingsActivity.ID_DETAIL).contains("文字回答"))
                check(text(SettingsActivity.ID_ELAPSED).startsWith("耗时"))
                checkNotNull(saveButton(activity.window.decorView)).performClick()
                check(text(SettingsActivity.ID_RESULT) == "测试成功")
            }
            code.set(401); gate = CountDownLatch(0)
            main { view<Button>(SettingsActivity.ID_TEST).performClick() }
            await("failure") { text(SettingsActivity.ID_RESULT) == "测试失败" }
            main { check(text(SettingsActivity.ID_DETAIL).contains("HTTP 401")) }
            code.set(200); gate = CountDownLatch(1); entered = CountDownLatch(1)
            main {
                view<EditText>(SettingsActivity.ID_MODEL).setText("probe-2")
                check(text(SettingsActivity.ID_RESULT).contains("配置已更改"))
                view<Button>(SettingsActivity.ID_TEST).performClick()
            }
            check(entered.await(5, TimeUnit.SECONDS))
            main { view<Button>(SettingsActivity.ID_CANCEL).performClick() }
            await("cancelled") { text(SettingsActivity.ID_RESULT) == "测试已取消" }
            val count = requests.get()
            main {
                view<EditText>(SettingsActivity.ID_KEY).setText("")
                view<Button>(SettingsActivity.ID_TEST).performClick()
                check(text(SettingsActivity.ID_RESULT) == "测试失败")
                check(text(SettingsActivity.ID_ELAPSED).isEmpty())
                check(view<View>(SettingsActivity.ID_PROGRESS).visibility == View.GONE)
            }
            check(requests.get() == count)
            return "PASS AI settings: visible progress/timer, success, HTTP 401, retained result after save, input invalidation, cancel, validation without request"
        } finally { gate.countDown(); main { activity.finish() }; Credentials(app).clear() }
    }
}
