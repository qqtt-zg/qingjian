//! 标题栏直接停止或重连，不弹连接面板；详细连接页保留兼容控制。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.qingjian.speech.MainActivity
import org.qingjian.speech.Pairing
import org.qingjian.speech.R

class ConnectionBar(
    private val activity: Activity,
    private val controls: ConnectionControls = ConnectionControls(activity),
    private val compact: Boolean = false,
    onScan: (() -> Unit)? = null,
) {
    private val ui = Views(activity)
    private val preferences = activity.getSharedPreferences("speech", 0)
    private var panel: ConnectionPanel? = null
    private val scan = {
        if(onScan != null) onScan() else activity.startActivity(Intent(activity, MainActivity::class.java)
            .putExtra("scan_now", true).putExtra("return_after_scan", true))
    }
    val statusView = ui.text("", 13f, true).apply {
        id = R.id.connectionSummary; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(0, 0, ui.dp(5), 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        if(compact) {
            isFocusable = true
            setOnClickListener {
                val state=ConnectionStatus.from(preferences.getString("connection_status","").orEmpty())
                if(state==ConnectionStatus.CONNECTED||state==ConnectionStatus.CONNECTING)controls.stop() else controls.reconnect()
            }
        }
    }
    val scanButton = ui.iconButton("扫码连接电脑", R.drawable.ic_scan_line) {
        scan()
    }.apply { id = R.id.scanConnect; tooltipText = "扫码并自动连接" }
    val reconnectButton = ui.iconButton("重新连接电脑", R.drawable.ic_reconnect) { controls.reconnect() }.apply {
        id = R.id.reconnectConnect; tooltipText = "重新连接电脑"
    }
    val stopButton = ui.iconButton("停止接收", R.drawable.ic_disconnect) { controls.stop() }.apply {
        id = R.id.stopConnect; tooltipText = "停止接收与朗读"
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key in listOf("status", "connection_status", "url", "retry_deadline", "retry_attempt")) statusView.post { refresh() }
    }
    private val refreshLoop=object:Runnable {override fun run(){refresh();statusView.postDelayed(this,1000)}}
    val view = ui.row().apply {
        id = R.id.connectionBar; setPadding(if(compact) 0 else ui.dp(18), 0, if(compact) 0 else ui.dp(8), 0)
        if(!compact) background = ui.shape(ui.color(R.color.chip_background), 0)
        if(compact) {
            statusView.textSize = 11f; statusView.maxLines = 1; statusView.gravity = Gravity.CENTER
            statusView.setPadding(ui.dp(3), 0, ui.dp(3), 0)
            statusView.background = RippleDrawable(ColorStateList.valueOf(0x1839532b), ui.shape(android.graphics.Color.TRANSPARENT, 11), null)
            addView(statusView, LinearLayout.LayoutParams(ui.dp(76), ui.dp(44)))
            scanButton.layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = ui.dp(2) }
            scanButton.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            scanButton.background = RippleDrawable(ColorStateList.valueOf(0x1839532b), ui.shape(android.graphics.Color.TRANSPARENT, 11), null)
            scanButton.setCompoundDrawables(ui.icon(R.drawable.ic_scan_line).apply { setBounds(0, 0, ui.dp(20), ui.dp(20)) }, null, null, null)
        } else addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        addView(scanButton)
        if(!compact) { addView(reconnectButton); addView(stopButton) }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { preferences.registerOnSharedPreferenceChangeListener(listener); statusView.post(refreshLoop) }
            override fun onViewDetachedFromWindow(v: View) { preferences.unregisterOnSharedPreferenceChangeListener(listener); statusView.removeCallbacks(refreshLoop); panel?.dismiss(); panel = null }
        })
    }

    private fun refresh() {
        val message = (preferences.getString("connection_status", null) ?: preferences.getString("status", "尚未连接电脑")).orEmpty()
        statusView.text = when {
            message.contains("失效") || message.contains("无效") -> "配对失效，请扫码"
            message.contains("重新连接") -> "正在重新连接…"
            message.contains("正在连接") -> "正在连接电脑…"
            message.contains("暂停") -> "已连接 · 电脑已暂停"
            message.contains("已连接") -> "已连接 · 接收中"
            message.contains("已停止") -> "已停止接收"
            else -> message.ifBlank { "尚未连接电脑" }
        }
        statusView.contentDescription = "电脑朗读状态：$message"; statusView.tooltipText = message
        if(compact) {
            val state = ConnectionStatus.from(message)
            statusView.text = if(state in listOf(ConnectionStatus.OFFLINE,ConnectionStatus.EXPIRED))"↻ 重新连接" else "${state.symbol} ${state.label}"
            val remaining=((preferences.getLong("retry_deadline",0)-android.os.SystemClock.elapsedRealtime()+999)/1000).coerceIn(0,60)
            if(state==ConnectionStatus.CONNECTING&&remaining>0)statusView.text="◌ 重连 ${remaining}s"
            statusView.setTextColor(ui.color(state.color))
            statusView.contentDescription = if(state in listOf(ConnectionStatus.CONNECTED,ConnectionStatus.CONNECTING))"电脑${state.label}，点击停止连接" else "重新连接电脑，配对失效时请扫码"
            if(state==ConnectionStatus.CONNECTING)statusView.contentDescription="正在连接，已重试${preferences.getInt("retry_attempt",0)}次，剩余${remaining}秒，点击停止连接"
        }
        reconnectButton.isEnabled = ConnectionControls.activeUrl(preferences).isNotBlank()
        reconnectButton.alpha = if(reconnectButton.isEnabled) 1f else 0.35f
    }
}
