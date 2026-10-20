//! 用户自带 API 的原生设置页，测试不自动保存，也不读取电脑配置或配对凭证。
package org.qingjian.speech.learning.ai

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.Views
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var address: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var result: TextView
    private lateinit var test: Button
    private lateinit var save: Button
    private lateinit var clear: Button
    private lateinit var cancel: Button
    private lateinit var progress: ProgressBar
    private lateinit var detail: TextView
    private lateinit var elapsed: TextView
    private lateinit var saved: TextView
    private lateinit var testSection: LinearLayout
    private lateinit var scroll: ScrollView
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var client: Client? = null
    private var testing = false
    private var cancelling = false
    private var started = 0L
    private var successfulTag: String? = null
    private val tick = object : Runnable {
        override fun run() {
            if(!testing || isDestroyed) return
            elapsed.text = "已等待 ${(SystemClock.elapsedRealtime() - started) / 1000} 秒"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        ui = Views(this)
        val column = ui.column()
        column.addView(ui.text("手机直接联网答疑，无需电脑在线。支持 OpenAI 兼容的 Chat Completions；仅支持其他协议的服务暂不可用。", muted = true))
        val credentials = Credentials(this)
        var loadError = ""
        val profile = try { credentials.load() } catch(error: Exception) { loadError = Client.errorMessage(error); Profile("", "", "") }
        column.addView(ui.text("API 地址（包含服务商要求的 /v1 等路径）"))
        address = ui.input("https://服务商地址/v1", profile.address).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; id = ID_ADDRESS }
        column.addView(address)
        column.addView(ui.text("模型 ID"))
        model = ui.input("按服务商文档填写", profile.model).apply { id = ID_MODEL }
        column.addView(model)
        column.addView(ui.text("API Key"))
        key = ui.input("填写你自己的密钥", profile.key).apply {
            id = ID_KEY; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false; importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        column.addView(key)
        column.addView(ui.text("密钥加密保存在本机，不写入安装包。问题与当前词汇会发送给你配置的服务；该服务可能按使用量收费。", 13f, true))
        val tools = android.widget.Switch(this).apply {
            text = "启用学习工具"; contentDescription = "启用学习工具"; minHeight = ui.dp(52)
            isChecked = org.qingjian.speech.learning.ai.tools.ToolSettings.enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, value -> org.qingjian.speech.learning.ai.tools.ToolSettings.save(this@SettingsActivity, value) }
        }
        column.addView(tools)
        column.addView(ui.caption("需要模型和中转站支持函数调用。最多进行3轮模型交互，只返回有限词库/测验数据；写入、练习和播放先显示操作卡。接口不兼容时可关闭，继续文字答疑。连接测试仅验证文字回复。"))
        saved = ui.text(loadError, 13f, true)
        column.addView(saved)
        save = ui.button("保存配置", true) {
            try {
                val value = profile()
                credentials.save(value)
                saved.text = if(successfulTag == tag(value)) "配置已保存，当前配置已测试通过" else "配置已保存；可在下方测试连接"
            } catch(error: Exception) { feedback("配置保存失败", Client.errorMessage(error), ERROR) }
        }
        column.addView(save)
        testSection = ui.card(column) { card ->
            test = ui.button("测试连接（发送一条短请求）", true) { testConnection() }.apply { id = ID_TEST }
            card.addView(test)
            val statusRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            progress = ProgressBar(this).apply { id = ID_PROGRESS; isIndeterminate = true; visibility = View.GONE; contentDescription = "正在测试连接" }
            statusRow.addView(progress, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { marginEnd = ui.dp(10) })
            result = ui.text("尚未测试连接", 16f).apply {
                id = ID_RESULT; isSaveEnabled = false; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            statusRow.addView(result, LinearLayout.LayoutParams(0, -2, 1f)); card.addView(statusRow)
            detail = ui.text("测试将发送一条短请求，不会自动保存配置。", 13f, true).apply { id = ID_DETAIL; isSaveEnabled = false }
            elapsed = ui.text("", 12f, true).apply { id = ID_ELAPSED; isSaveEnabled = false }
            card.addView(detail); card.addView(elapsed)
            cancel = ui.button("取消测试") {
                cancelling = true; result.text = "正在取消测试…"; detail.text = "正在停止等待本次回答"
                cancel.isEnabled = false; client?.cancel()
            }.apply { id = ID_CANCEL; visibility = View.GONE }
            card.addView(cancel)
        }
        clear = ui.button("清除 AI 配置") {
            try {
                credentials.clear(); address.setText(""); model.setText(""); key.setText("")
                saved.text = "AI 配置已清除，学习记录保留"
            } catch(error: Exception) { feedback("配置清除失败", Client.errorMessage(error), ERROR) }
        }.apply { id = ID_CLEAR }
        column.addView(clear)
        val root = ui.page("AI 设置", column)
        scroll = root.getChildAt(1) as ScrollView
        setContentView(root)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if(!testing) {
                    successfulTag = null; result.text = "配置已更改，请重新测试"
                    result.setTextColor(getColor(org.qingjian.speech.R.color.text_primary))
                    detail.text = "测试结果仅对应测试时填写的地址、模型和密钥。"
                    elapsed.text = ""; test.text = "测试连接（发送一条短请求）"; saved.text = ""
                }
            }
        }
        listOf(address, model, key).forEach { it.addTextChangedListener(watcher) }
        if(state?.getBoolean("test_running") == true) feedback("测试已中断", "页面重建取消了上一次测试，请重新测试。", ERROR)
    }

    private fun profile() = Profile(address.text.toString().trim(), model.text.toString().trim(), key.text.toString().trim())

    private fun testConnection() {
        if(testing) return
        val profile = profile()
        hideKeyboard()
        try { profile.validate() } catch(error: Exception) {
            successfulTag = null; elapsed.text = ""; test.text = "重新测试连接"
            feedback("测试失败", Client.errorMessage(error), ERROR); return
        }
        testing = true; cancelling = false; successfulTag = null; started = SystemClock.elapsedRealtime()
        busy(true); result.text = "正在测试连接"; detail.text = "正在连接 AI 服务…"
        result.setTextColor(getColor(org.qingjian.speech.R.color.text_primary))
        handler.post(tick); revealFeedback()
        val request = Client(); client = request
        worker.execute {
            var ok = false
            val message = try {
                request.complete(profile, JSONArray().put(JSONObject().put("role", "user").put("content", "请只回复：连接成功"))) { stage ->
                    runOnUiThread { if(!isDestroyed && testing && !cancelling) detail.text = stage }
                }
                ok = true
                "已收到模型的文字回答。点击“保存配置”后即可用于答疑；此测试不代表音频评估可用。"
            } catch(error: Exception) { Client.errorMessage(error) }
            runOnUiThread {
                if(!isDestroyed && !isFinishing) {
                    testing = false; client = null; handler.removeCallbacks(tick); busy(false)
                    elapsed.text = String.format(Locale.ROOT, "耗时 %.1f 秒", (SystemClock.elapsedRealtime() - started) / 1000.0)
                    if(cancelling) feedback("测试已取消", "可以修改配置后重新测试。", getColor(org.qingjian.speech.R.color.text_secondary))
                    else {
                        if(ok) successfulTag = tag(profile)
                        feedback(if(ok) "测试成功" else "测试失败", message, if(ok) SUCCESS else ERROR)
                    }
                    test.text = if(ok && !cancelling) "再次测试连接" else "重新测试连接"
                    Toast.makeText(this, result.text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun busy(value: Boolean) {
        listOf(address, model, key).forEach { it.isEnabled = !value }
        test.isEnabled = !value; save.isEnabled = !value; clear.isEnabled = !value
        test.text = if(value) "测试中…" else "测试连接（发送一条短请求）"
        progress.visibility = if(value) View.VISIBLE else View.GONE
        cancel.visibility = if(value) View.VISIBLE else View.GONE; cancel.isEnabled = value
    }

    private fun feedback(title: String, message: String, color: Int) {
        result.text = title; result.setTextColor(color); detail.text = message
        revealFeedback()
    }

    private fun revealFeedback() { scroll.post { if(!isDestroyed && !isFinishing) scroll.smoothScrollTo(0, testSection.top) } }

    private fun hideKeyboard() {
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
    }

    private fun tag(profile: Profile): String = MessageDigest.getInstance("SHA-256")
        .digest((profile.address + "\u0000" + profile.model + "\u0000" + profile.key).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("test_running", testing); super.onSaveInstanceState(outState) }

    override fun onDestroy() {
        testing = false; handler.removeCallbacks(tick); client?.cancel(); worker.shutdownNow(); super.onDestroy()
    }

    companion object {
        const val ID_ADDRESS = 0x005a1001
        const val ID_MODEL = 0x005a1002
        const val ID_KEY = 0x005a1003
        const val ID_RESULT = 0x005a1004
        const val ID_TEST = 0x005a1005
        const val ID_PROGRESS = 0x005a1006
        const val ID_CANCEL = 0x005a1007
        const val ID_DETAIL = 0x005a1008
        const val ID_ELAPSED = 0x005a1009
        const val ID_CLEAR = 0x005a100a
        private const val SUCCESS = 0xff176844.toInt()
        private const val ERROR = 0xffb3261e.toInt()
    }
}
