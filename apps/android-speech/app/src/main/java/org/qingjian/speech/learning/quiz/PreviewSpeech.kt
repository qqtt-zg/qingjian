//! 选项试听复用内置音色、音量和语速；请求编号隔离旧回调，不写词汇播放计数。
package org.qingjian.speech.learning.quiz

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.qingjian.speech.R
import org.qingjian.speech.SpeechPlayer
import org.qingjian.speech.learning.Views

class PreviewSpeech(private val activity: Activity,private val canPlay:()->Boolean={true}) {
    private val ui = Views(activity)
    private val main = Handler(Looper.getMainLooper())
    private var player: SpeechPlayer? = null
    private var current = -1L
    private var activeKey = ""
    private var status = "点喇叭试听，不会提交答案"
    private val buttons = mutableListOf<SpeechBinding>()
    private val labels = mutableListOf<Pair<TextView,Boolean>>()

    fun clearBindings(root: android.view.View?=null) {
        if(root==null){buttons.clear();labels.clear();return}
        fun belongs(view:android.view.View):Boolean {var current:android.view.View?=view;while(current!=null){if(current===root)return true;current=current.parent as? android.view.View};return false}
        buttons.removeAll {belongs(it.button)};labels.removeAll {belongs(it.first)}
    }
    fun isActive(key: String) = activeKey==key

    fun button(key: String, text: String, label:String="选项"): Button = ui.iconButton("朗读$label：$text", R.drawable.ic_speaker) { toggle(key, text) }.apply {
        layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(48))
        setPadding(ui.dp(11), ui.dp(12), ui.dp(11), ui.dp(12))
        tooltipText = "朗读$label，再点停止"
        buttons += SpeechBinding(this,key,text,label); refresh()
    }

    fun label(maskText: Boolean = false): TextView = ui.caption(if(maskText&&activeText.isNotBlank())status.replace(activeText,"英文题目") else status).apply {
        accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        labels += this to maskText
    }

    fun toggle(key: String, text: String) {
        if(!canPlay()){android.widget.Toast.makeText(activity,"请先停止录音，再试听示范",android.widget.Toast.LENGTH_SHORT).show();return}
        if(activeKey == key) { stop(); return }
        require(text.length in 1..4000)
        if(player == null) {
            player = SpeechPlayer(activity.applicationContext, {}, {}).also { output ->
                output.onEvent = { id, message -> main.post {
                    if(player === output && id == current) {
                        status = when {
                            message.contains("朗读完成") -> "朗读完成"
                            message.startsWith("正在朗读") -> "正在朗读：$activeText"
                            else -> message
                        }
                        if(message.contains("朗读完成") || message.contains("失败") || message.contains("无法播放") || message.contains("打断") || message.contains("超时")) { activeKey = ""; current = -1 }
                        refresh()
                    }
                } }
            }
        }
        activeText = text; activeKey = key; status = "正在准备朗读：$text"
        current = player!!.submit(text, learn = false); refresh()
    }

    private var activeText = ""

    fun stop() { current = -1; activeKey = ""; player?.cancelPending(); status = "已停止朗读"; refresh() }

    fun close() { stop(); player?.onEvent = null; player?.close(); player = null; clearBindings() }

    private fun refresh() {
        for((button, key, text,label) in buttons) {
            val selected = key == activeKey
            button.isSelected = selected
            button.contentDescription = if(selected) "停止朗读$label：$text" else "朗读$label：$text"
            button.background = ui.shape(ui.color(if(selected) R.color.system_blue else R.color.chip_background), 12)
            button.setCompoundDrawables(ui.icon(if(selected) R.drawable.ic_audio_stop else R.drawable.ic_speaker,
                if(selected) Color.WHITE else ui.color(R.color.system_blue)), null, null, null)
        }
        labels.forEach { (label,masked) -> label.text=if(masked&&activeText.isNotBlank())status.replace(activeText,"英文题目") else status }
    }
}
