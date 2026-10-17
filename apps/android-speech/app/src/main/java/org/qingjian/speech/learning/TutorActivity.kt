//! 带当前词汇与有限对话历史的英语答疑页；回答按纯文字展示并保存在本机。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.qingjian.speech.learning.ai.Session
import org.qingjian.speech.learning.ai.SettingsActivity

class TutorActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var session: Session
    private lateinit var column: LinearLayout
    private lateinit var input: EditText
    private var draft = ""

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = Views(this)
        session = (lastNonConfigurationInstance as? Session) ?: Session(applicationContext, intent.getLongExtra("word_id", 0))
        draft = (state?.getString("draft") ?: intent.getStringExtra("question")).orEmpty().take(4000)
        render()
    }

    override fun onResume() { super.onResume(); session.changed = { render() }; render() }
    override fun onPause() { draft = input.text.toString(); session.changed = null; super.onPause() }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onSaveInstanceState(out: Bundle) { out.putString("draft", input.text.toString()); super.onSaveInstanceState(out) }
    override fun onDestroy() { if(!isChangingConfigurations) session.close(); super.onDestroy() }

    private fun render() {
        if(::input.isInitialized) draft = input.text.toString()
        val store = LearningStore.get(this)
        val word = store.word(session.wordId)
        column = ui.column()
        column.addView(ui.text(word?.let { "当前词汇：${it.english}\n笔记：${it.meaning.ifEmpty { "尚未填写" }}" } ?: "英语学习答疑", muted = true))
        column.addView(ui.text("当前词汇、笔记及最近对话会发送给所选 AI 服务。已收到的回答可离线查看。", 13f, true))
        column.addView(ui.button("AI 设置") { startActivity(Intent(this, SettingsActivity::class.java)) }.apply { isEnabled = !session.busy })
        val messages = store.messages(session.topic)
        for(entry in messages) ui.card(column) { card ->
            card.addView(ui.text(if(entry.role == "user") "我" else "AI 老师", 13f, true))
            card.addView(ui.text(entry.text).apply { setTextIsSelectable(true) })
            if(entry.state != "done") card.addView(ui.text("尚未取得回答 · 已本地保存", 12f, true))
        }
        column.addView(ui.text(session.notice.ifEmpty { "先回答你的疑问，再按需举例和练习。AI 回答可能有误。" }, 13f, true).apply {
            id = ID_STATUS; accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        input = ui.input("例如：realize 和 implement 怎么选？", draft, true).apply { id = ID_QUESTION }
        column.addView(input)
        if(messages.isEmpty() && word != null) column.addView(ui.button("填入问题：这个词怎么用？") {
            input.setText("请简短解释 ${word.english} 的常见用法，给一个简单例句和中文翻译。")
        })
        column.addView(ui.button("发送问题", true) {
            val question = input.text.toString()
            if(question.isNotBlank() && question.length <= 4000) { input.setText(""); draft = "" }
            session.send(question)
        }.apply { isEnabled = !session.busy; id = ID_SEND })
        column.addView(ui.button("只保存问题，稍后再问") {
            val question = input.text.toString().trim()
            if(question.isNotEmpty() && question.length <= 4000) {
                store.question(session.topic, question); input.setText(""); draft = ""; render()
            } else input.error = "请输入 1～4000 字的问题"
        }.apply { isEnabled = !session.busy; id = ID_SAVE })
        messages.lastOrNull { it.role == "user" && it.state != "done" }?.let { pending ->
            column.addView(ui.button("发送／重试最近保存的问题") { session.send(pending.text, pending.id) }.apply { isEnabled = !session.busy })
        }
        column.addView(ui.button("清空本页对话") {
            AlertDialog.Builder(this).setMessage("清空本页保存的问题和回答？词汇与 AI 配置保留。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> store.clearMessages(session.topic); render() }.show()
        }.apply { isEnabled = !session.busy })
        setContentView(ui.page("问一问", column))
    }

    companion object {
        const val ID_QUESTION = 0x005a2001
        const val ID_SEND = 0x005a2002
        const val ID_SAVE = 0x005a2003
        const val ID_STATUS = 0x005a2004
    }
}
