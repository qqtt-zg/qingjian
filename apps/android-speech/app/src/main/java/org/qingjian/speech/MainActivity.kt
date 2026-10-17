//! 统一设置与低频朗读配置页；共用偏好和声音控制，不读取剪贴板或修改系统 TTS。
package org.qingjian.speech

import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.Navigation
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.DataSettingsActivity
import org.qingjian.speech.learning.ai.Credentials
import org.qingjian.speech.learning.ai.SettingsActivity
import org.qingjian.speech.rate.SpeechRate
import org.qingjian.speech.rate.RateControls

open class MainActivity : ComponentActivity() {
    protected open val settingsPage = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var address: EditText
    private lateinit var received: TextView
    private lateinit var playback: TextView
    private lateinit var voicePicker: Spinner
    private lateinit var gainAmount: TextView
    private lateinit var gainSeekBar: SeekBar
    private lateinit var presetButtons: List<Button>
    private var voiceKey = ""
    private var syncingVoices = false
    private var voiceSummary: TextView? = null
    private var gainSummary: TextView? = null
    private var rateSummary: TextView? = null
    private var rateControls: RateControls? = null
    private var updateSummary: TextView? = null
    private var aiSummary: TextView? = null
    private val scanner = registerForActivityResult(ScanContract()) { result -> acceptScan(result.contents) }

    /** 扫码结果只作为配对地址校验，不打开浏览器或执行二维码中的其他内容。 */
    fun acceptScan(contents: String?) {
        if (contents == null) { report("已取消扫码，原连接地址保留"); return }
        connect(contents)
    }

    private fun report(message: String) {
        getSharedPreferences("speech", 0).edit().putString("status", message).apply()
        status.text = message
        updateStatusColor(message)
    }

    private fun updateStatusColor(message: String) {
        val color = when {
            message.contains("已连接") || message.contains("朗读中") -> getColor(R.color.system_green)
            message.contains("正在连接") -> getColor(R.color.system_blue)
            message.contains("失败") || message.contains("错误") || message.contains("无效") -> getColor(R.color.system_red)
            else -> getColor(R.color.text_secondary)
        }
        status.setTextColor(color)
    }

    private fun connect(value: String) {
        try {
            val url = value.trim()
            Pairing.parse(url)
            getSharedPreferences("speech", 0).edit().putString("url", url).apply()
            address.setText(url)
            report("正在连接…")
            startSpeech("connect")
        } catch (_: Exception) {
            report("连接未启动，请先扫码，或在朗读设置检查配对地址")
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("speech", 0)
            val currentStatus = prefs.getString("status", "准备就绪") ?: "准备就绪"
            status.text = currentStatus
            updateStatusColor(currentStatus)
            received.text = "最近接收：" + (prefs.getString("last_receive", "尚未收到电脑请求") ?: "尚未收到电脑请求")
            playback.text = "最近朗读：" + (prefs.getString("last_playback", "尚未试播或朗读") ?: "尚未试播或朗读")
            gainSummary?.text = "音量增强：${(prefs.getFloat("gain", 1f).coerceIn(0.5f, 4f) * 100).toInt()}%"
            rateSummary?.text = "朗读语速：${SpeechRate.label(SpeechRate.percent(this@MainActivity))}"
            rateControls?.refresh()
            updateSummary?.text = getSharedPreferences("app_update", 0).getString("status", "连接电脑后可检查应用更新")
            refreshVoices()
            handler.postDelayed(this, 1000)
        }
    }

    private fun refreshVoices() {
        if (!::voicePicker.isInitialized) return
        val prefs = getSharedPreferences("speech", 0)
        val pack = prefs.getString("pack", "builtin") ?: "builtin"
        val names = VoiceLibrary.names(this, pack)
        val key = pack + ":" + names.size
        val index = prefs.getInt("voice", 1).coerceIn(0, names.lastIndex)
        syncingVoices = true
        try {
            if (key != voiceKey) {
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
                voicePicker.adapter = adapter
                voiceKey = key
            }
            if (voicePicker.selectedItemPosition != index) voicePicker.setSelection(index)
        } finally {
            syncingVoices = false
        }
        voiceSummary?.text = "当前音色：${names[index]}"
        findViewById<TextView>(R.id.tvEngineName).text = when {
            pack != "builtin" -> "导入音色 · sherpa-onnx"
            BundledVoice.engineKey(index) == "kokoro-heart" -> "Kokoro · af_heart"
            else -> "KittenTTS"
        }
    }

    private fun savePairingAddress() {
        val value = address.text.toString().trim()
        try {
            Pairing.parse(value)
            getSharedPreferences("speech", 0).edit().putString("url", value).apply()
            Toast.makeText(this, "配对地址已保存，返回设置后点击连接生效", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { address.error = "请填写电脑配对页的完整 HTTPS 地址" }
    }

    private fun applyGain(percent: Int, fromUser: Boolean = false) {
        val clamped = percent.coerceIn(50, 400)
        val progress = clamped - 50
        if (gainSeekBar.progress != progress) {
            gainSeekBar.progress = progress
        }
        gainAmount.text = "$clamped%"
        gainSummary?.text = "音量增强：$clamped%"
        presetButtons.forEach { btn ->
            val p = btn.text.toString().replace("%", "").toIntOrNull() ?: -1
            btn.isSelected = (p == clamped)
        }
        getSharedPreferences("speech", 0).edit().putFloat("gain", clamped / 100f).apply()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        BundledVoice.initializeSelection(this)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        val ui = Views(this)
        val root = ui.column()
        if(settingsPage) root.addView(ui.header("朗读设置"))
        root.addView(layoutInflater.inflate(R.layout.activity_main, root, false), LinearLayout.LayoutParams(-1, 0, 1f))
        if(!settingsPage) root.addView(Navigation.create(this, "settings") { page ->
            startActivity(Intent(this, LearningActivity::class.java).putExtra("page", page)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        })
        ui.insets(root)
        setContentView(root)

        val prefs = getSharedPreferences("speech", 0)

        // 控件绑定
        status = findViewById(R.id.tvStatus)
        address = findViewById(R.id.etAddress)
        received = findViewById(R.id.tvReceived)
        playback = findViewById(R.id.tvPlayback)
        voicePicker = findViewById(R.id.spVoicePicker)
        gainAmount = findViewById(R.id.tvGainAmount)
        gainSeekBar = findViewById(R.id.sbGain)

        address.setText(prefs.getString("url", ""))

        // 扫码主按钮 (启动全新现代化沉浸式扫码界面)
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            scanner.launch(
                ScanOptions()
                    .setCaptureActivity(ScanActivity::class.java)
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }

        // 连接与停止
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if(settingsPage) savePairingAddress() else connect(address.text.toString())
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, ReceiverService::class.java))
            report("已停止接收")
        }

        // 音色选择与试播
        voicePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if(!syncingVoices) prefs.edit().putInt("voice", position).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refreshVoices()

        findViewById<Button>(R.id.btnTestSpeech).setOnClickListener {
            startSpeech("test")
        }

        // 响度滑块与预设快捷键
        presetButtons = listOf(
            findViewById(R.id.btnGain50),
            findViewById(R.id.btnGain100),
            findViewById(R.id.btnGain180),
            findViewById(R.id.btnGain250),
            findViewById(R.id.btnGain400)
        )

        presetButtons.forEach { btn ->
            btn.setOnClickListener {
                val p = btn.text.toString().replace("%", "").toIntOrNull() ?: 100
                applyGain(p, fromUser = true)
            }
        }

        gainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                if (user) applyGain(value + 50, fromUser = true)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        val initialGain = ((prefs.getFloat("gain", 1f).coerceIn(0.5f, 4f)) * 100).toInt()
        applyGain(initialGain)

        // 后台省电白名单跳转
        val openBatterySettings = {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.btnBatteryHeader).apply {
            text = "朗读设置"
            setOnClickListener { startActivity(Intent(this@MainActivity, SpeechSettingsActivity::class.java)) }
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener { openBatterySettings() }

        // 开源许可说明弹窗
        findViewById<View>(R.id.rowLicense).setOnClickListener {
            val notices = assets.open("THIRD-PARTY.txt").bufferedReader().use { it.readText() }
            android.app.AlertDialog.Builder(this)
                .setTitle("开源来源与许可说明")
                .setMessage(notices)
                .setPositiveButton("关闭", null)
                .show()
        }
        arrangePage(ui)

    }

    private fun startSpeech(action: String) {
        // 只查看 AI 或应用选项时，不要求朗读会话的通知权限。
        if(Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        startForegroundService(Intent(this, ReceiverService::class.java).setAction(action))
    }

    override fun onResume() {
        super.onResume()
        if(!settingsPage) address.setText(getSharedPreferences("speech", 0).getString("url", ""))
        aiSummary?.text = if(runCatching { Credentials(this).load().validate() }.isSuccess) "已保存 API 配置" else "尚未配置 AI 接口"
        applyGain((getSharedPreferences("speech", 0).getFloat("gain", 1f).coerceIn(0.5f, 4f) * 100).toInt())
        handler.post(refresh)
    }

    private fun arrangePage(ui: Views) {
        val column = findViewById<LinearLayout>(R.id.mainColumn)
        val voiceCard = voicePicker.parent.parent as View
        val diagnosticCard = received.parent as View
        val systemCard = findViewById<View>(R.id.rowBattery).parent as View
        val gainCard = gainSeekBar.parent as View
        fun show(view: View, visible: Boolean) { view.visibility = if(visible) View.VISIBLE else View.GONE }
        fun card(view: View, visible: Boolean) {
            show(view, visible)
            val parent = view.parent as ViewGroup
            val preceding = parent.getChildAt(parent.indexOfChild(view) - 1)
            if(preceding is TextView) show(preceding, visible)
        }
        card(voiceCard, settingsPage)
        card(diagnosticCard, settingsPage)
        card(systemCard, settingsPage)
        card(gainCard, settingsPage)
        show(findViewById(R.id.connectionStatusRow), !settingsPage)
        show(findViewById(R.id.connectionStatusDivider), !settingsPage)
        show(address, settingsPage)
        show(findViewById(R.id.tvPairingLabel), settingsPage)
        show(findViewById(R.id.gainPresets), settingsPage)
        show(findViewById(R.id.tvGainHelp), settingsPage)
        show(findViewById(R.id.btnBatteryHeader), false)
        show(findViewById(R.id.btnScan), !settingsPage)
        show(findViewById(R.id.btnStop), !settingsPage)
        val license = findViewById<View>(R.id.rowLicense)
        val licenseParent = license.parent as ViewGroup
        show(license, false)
        show(licenseParent.getChildAt(licenseParent.indexOfChild(license) - 1), false)
        if(settingsPage) {
            show(findViewById<View>(R.id.tvHeaderTitle).parent.parent as View, false)
            findViewById<TextView>(R.id.tvPairingLabel).text = "手动配对地址（保存后返回设置，点击连接生效）"
            findViewById<Button>(R.id.btnConnect).text = "保存配对地址"
            val voiceContainer = voiceCard as ViewGroup
            rateControls = RateControls(this).also { voiceContainer.addView(it.view, voiceContainer.indexOfChild(findViewById(R.id.btnTestSpeech))) }
            return
        }
        findViewById<TextView>(R.id.tvHeaderTitle).text = "设置"
        findViewById<TextView>(R.id.tvHeaderSubtitle).text = "朗读、AI 与应用选项"
        column.addView(ui.text("朗读与连接", 20f), 1)
        val quick = ui.card(column) { content ->
            voiceSummary = ui.text("").also { content.addView(it) }
            gainSummary = ui.text("", 13f, true).also { content.addView(it) }
            rateSummary = ui.text("", 13f, true).also { content.addView(it) }
            content.addView(ui.button("朗读设置") { startActivity(Intent(this, SpeechSettingsActivity::class.java)) })
        }
        column.removeView(quick)
        column.addView(quick, column.indexOfChild(voiceCard))
        ui.card(column) { content ->
            content.addView(ui.text("AI 老师", 20f))
            aiSummary = ui.text("", 13f, true).also { content.addView(it) }
            content.addView(ui.button("配置与测试 AI") { startActivity(Intent(this, SettingsActivity::class.java)) })
        }
        ui.card(column) { content ->
            content.addView(ui.text("应用更新", 20f))
            updateSummary = ui.text("", 13f, true).also { content.addView(it) }
            content.addView(ui.button("应用更新") { startActivity(Intent(this, org.qingjian.speech.update.UpdateActivity::class.java)) })
        }
        column.addView(ui.button("数据与关于") { startActivity(Intent(this, DataSettingsActivity::class.java)) })
        refreshVoices()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
