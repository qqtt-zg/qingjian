//! 主页面的连接操作面板；复用真实接收服务，关闭时释放偏好监听。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import org.qingjian.speech.Pairing
import org.qingjian.speech.R

class ConnectionPanel(private val activity: Activity, private val controls: ConnectionControls, private val scan: () -> Unit) {
    private val ui = Views(activity)
    private val prefs = activity.getSharedPreferences("speech", 0)
    private val dialog = Dialog(activity)
    private val summary = ui.text("", 17f)
    private val detail = ui.caption("")
    private val received = ui.text("", 13f, true)
    private val played = ui.text("", 13f, true)
    private val primary = ui.button("重新连接", true) { if(state() == ConnectionStatus.EXPIRED) { dismiss(); scan() } else controls.reconnect() }
    private val stop = ui.button("停止连接") { controls.stop() }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key in listOf("status", "connection_status", "url", "last_receive", "last_playback")) summary.post { if(dialog.isShowing) refresh() }
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(20))
            background = ui.shape(ui.color(R.color.screen_background), 24)
        }
        root.addView(ui.row().apply {
            addView(ui.title("电脑连接").apply { textSize = 20f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton("关闭连接面板", R.drawable.ic_close) { dismiss() })
        })
        ui.card(root) { card ->
            card.background = ui.shape(ui.color(R.color.chip_background), 14)
            summary.setTypeface(null, Typeface.BOLD); summary.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            card.addView(summary); card.addView(detail)
        }
        root.addView(ui.caption("最近接收")); root.addView(received); ui.divider(root)
        root.addView(ui.caption("最近朗读")); root.addView(played)
        root.addView(primary); root.addView(stop)
        root.addView(ui.caption("停止连接不影响本机学习；音色和语速在「学习 → 设置 → 朗读设置」调整。"))
        dialog.setContentView(root)
        dialog.setOnShowListener { prefs.registerOnSharedPreferenceChangeListener(listener); refresh() }
        dialog.setOnDismissListener { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun message() = (prefs.getString("connection_status", null) ?: prefs.getString("status", "尚未连接电脑")).orEmpty()
    private fun state() = ConnectionStatus.from(message())

    private fun refresh() {
        val state = state()
        summary.text = when(state) {
            ConnectionStatus.CONNECTED -> "电脑已连接"
            ConnectionStatus.CONNECTING -> "正在连接电脑…"
            ConnectionStatus.EXPIRED -> "配对地址已失效"
            ConnectionStatus.OFFLINE -> "电脑未连接"
        }
        summary.setTextColor(ui.color(state.color))
        detail.text = message().ifBlank { "本机词汇仍可离线学习" }
        received.text = prefs.getString("last_receive", "尚未接收电脑朗读")
        played.text = prefs.getString("last_playback", "尚未试播或朗读")
        val expired = state == ConnectionStatus.EXPIRED
        primary.text = if(expired) "重新扫码" else if(state == ConnectionStatus.CONNECTING) "连接中…" else "重新连接"
        primary.contentDescription = if(expired) "重新扫码连接电脑" else "重新连接电脑"
        primary.setCompoundDrawables(ui.icon(if(expired) R.drawable.ic_scan_line else R.drawable.ic_reconnect, Color.WHITE), null, null, null)
        primary.isEnabled = expired || (state != ConnectionStatus.CONNECTING && runCatching { Pairing.parse(prefs.getString("url", "").orEmpty()) }.isSuccess)
        stop.isEnabled = state in listOf(ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING)
        stop.setTextColor(ui.color(R.color.system_red))
        stop.setCompoundDrawables(ui.icon(R.drawable.ic_disconnect, ui.color(R.color.system_red)), null, null, null)
    }

    fun show() {
        if(activity.isFinishing || activity.isDestroyed) return
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
            setLayout(minOf(activity.resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(480)), -2)
        }
    }

    fun dismiss() { dialog.dismiss() }
}
