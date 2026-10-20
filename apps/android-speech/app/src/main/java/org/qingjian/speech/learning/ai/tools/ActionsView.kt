//! 自动执行后的结果回执；新增词可撤销，练习直接作答，旧版未执行草稿保留手动入口。
package org.qingjian.speech.learning.ai.tools

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import org.qingjian.speech.ReceiverService
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.quiz.InlinePractice
import org.qingjian.speech.learning.quiz.PreviewSpeech

class ActionsView(private val activity: Activity, private val speech: PreviewSpeech, private val changed: () -> Unit, private val openWord: ((Long)->Unit)? = null, private val ask: (String) -> Unit) {
    private val ui = Views(activity)
    private val store = ActionStore(activity)

    fun render(parent: LinearLayout, topic: String, interactive: Boolean, questionId: Long, activeQuestion: Long): View? {
        val actions = store.forTopic(topic).filter { it.questionId == questionId }
        var first: View? = null
        for(original in actions) {
            if(original.kind == "request_pronunciation" && original.automatic && original.state == "pending" && original.questionId == activeQuestion && interactive) submitVoice(original)
            val action = store.get(original.id) ?: continue
            if(action.kind == "prepare_practice" && action.state == "applied") {
                val before = parent.childCount
                InlinePractice(activity, speech, changed, ask).render(parent, action, interactive)
                if(parent.childCount > before) first = parent.getChildAt(before)
                continue
            }
            val card = ui.card(parent) { card ->
                card.background = ui.shape(ui.color(org.qingjian.speech.R.color.chip_background), 14)
                val label = when(action.state) {
                    "undone" -> "已撤销本次新增"
                    "dismissed" -> "已忽略此前操作"
                    "failed" -> "操作未完成，可手动重试"
                    "applied" -> when(action.kind) { "prepare_word_card" -> if(action.createdWord) "已加入待学习" else "已在词库，原笔记保留"; else -> "朗读请求已提交" }
                    else -> if(action.automatic) "操作尚未完成" else "此前准备的操作尚未执行"
                }
                val heading = ui.row()
                heading.addView(ui.text(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(heading)
                card.addView(ui.caption(action.payload.optString("english").ifBlank { "学习练习" } + if(action.kind == "prepare_word_card") " · " + action.payload.optString("meaning").take(80) else ""))
                if(action.kind == "prepare_word_card" && action.state == "applied") {
                    card.addView(ui.button("查看词卡") { if(openWord!=null)openWord.invoke(action.wordId) else activity.startActivity(Intent(activity, WordStudyActivity::class.java).putExtra("word_id", action.wordId)) }.apply {
                        isEnabled=interactive
                        minHeight = ui.dp(36); textSize = 12f; setPadding(ui.dp(8), 0, ui.dp(8), 0); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    })
                    if(action.createdWord) heading.addView(ui.button("撤销") {
                        val undone = store.undoCard(action.id)
                        if(!undone) Toast.makeText(activity, "词卡已有修改或后续学习，已保留，未删除任何资料。", Toast.LENGTH_LONG).show()
                        changed()
                    }.apply { isEnabled = interactive; contentDescription = "撤销本次新增：${action.payload.optString("english")}"; textSize = 12f; minHeight = ui.dp(36); setPadding(ui.dp(8), 0, ui.dp(8), 0) }, LinearLayout.LayoutParams(ui.dp(64), -2))
                    card.addView(ui.caption("加入待学习不代表已经掌握。"))
                }
                if(action.kind == "request_pronunciation" && action.state == "applied") card.addView(ui.caption("已提交不等于已播放完成；以播放器反馈为准。"))
                if(action.state in listOf("pending", "failed")) {
                    card.addView(ui.button(when(action.kind) { "prepare_word_card" -> "保存此前词卡"; "prepare_practice" -> "重新准备练习"; else -> "继续朗读请求" }) {
                        store.retry(action.id)
                        try {
                            when(action.kind) {
                                "prepare_word_card" -> store.saveCard(action.id)
                                "prepare_practice" -> store.practice(action.id)
                                else -> submitVoice(checkNotNull(store.get(action.id)))
                            }
                        } catch(_: Exception) { Toast.makeText(activity, "操作未完成，请补充笔记或重新提问。", Toast.LENGTH_LONG).show() }
                        changed()
                    }.apply { isEnabled = interactive })
                    card.addView(ui.button("忽略此前操作") { store.dismiss(action.id); changed() }.apply { isEnabled = interactive })
                }
            }
            if(first == null) first = card
        }
        return first
    }

    private fun submitVoice(action: Action) {
        try {
            val text = store.claimPronunciation(action.id)
            try { activity.startForegroundService(Intent(activity, ReceiverService::class.java).setAction("word").putExtra("text", text)) }
            catch(error: Exception) { store.releasePronunciation(action.id); store.failed(action.id); throw error }
        } catch(_: Exception) {
            Toast.makeText(activity, "朗读未提交，请回到前台后手动重试。", Toast.LENGTH_LONG).show()
        }
    }
}
