//! 词汇学习详情：引导选词、搭配和用法提问，并展示本机已保存的讲解。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import org.qingjian.speech.ReceiverService
import org.qingjian.speech.learning.oral.OralActivity

class WordStudyActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var other: EditText
    private lateinit var context: EditText
    private var otherDraft = ""
    private var contextDraft = ""
    private var wordId = 0L
    private val store by lazy { LearningStore.get(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = Views(this)
        wordId = intent.getLongExtra("word_id", 0)
        otherDraft = state?.getString("other").orEmpty()
        contextDraft = state?.getString("context").orEmpty()
    }

    override fun onResume() { super.onResume(); render() }

    override fun onPause() { rememberDrafts(); super.onPause() }

    override fun onSaveInstanceState(out: Bundle) {
        rememberDrafts(); out.putString("other", otherDraft); out.putString("context", contextDraft)
        super.onSaveInstanceState(out)
    }

    private fun rememberDrafts() {
        if(::other.isInitialized) otherDraft = other.text.toString()
        if(::context.isInitialized) contextDraft = context.text.toString()
    }

    private fun tutor(question: String = "") {
        startActivity(Intent(this, TutorActivity::class.java).putExtra("word_id", wordId).putExtra("question", question))
    }

    private fun render() {
        val word = store.word(wordId)
        if(word == null) { finish(); return }
        rememberDrafts()
        val column = ui.column()
        column.addView(ui.title(word.english))
        column.addView(ui.text(word.meaning.ifBlank { "还没有中文笔记，可先从词性与用法开始。" }, muted = true))
        column.addView(ui.text("收到 ${word.received} 次 · 完整播放 ${word.played} 次 · 自评复习 ${word.reviewed} 次", 12f, true))
        column.addView(ui.button("朗读这个词") {
            startForegroundService(Intent(this, ReceiverService::class.java).setAction("word").putExtra("text", word.english))
        })
        column.addView(ui.button("录音跟读") { startActivity(Intent(this, OralActivity::class.java).putExtra("word_id", wordId)) })
        ui.card(column) { card ->
            card.addView(ui.text("不知道从哪里学？", 20f))
            card.addView(ui.text("选一个方向，先查看问题草稿，再决定是否发送给 AI。已收到的回答会保存在手机。", 13f, true))
            for(direction in listOf(StudyDirection.BASICS, StudyDirection.COLLOCATIONS, StudyDirection.MISTAKES)) {
                card.addView(ui.button(direction.title) { tutor(direction.question(word.english)) })
            }
        }
        ui.card(column) { card ->
            card.addView(ui.text("相近词怎么选？", 20f))
            card.addView(ui.text("例如 realize 与 implement。补充想表达的意思或句子，辨析会更贴近你的疑问。", 13f, true))
            other = ui.input("另一个词或短语", otherDraft).apply { id = ID_OTHER }
            context = ui.input("语境或想表达的意思（可留空）", contextDraft, true).apply { id = ID_CONTEXT }
            card.addView(other); card.addView(context)
            card.addView(ui.button("准备辨析问题", true) {
                if(other.text.isBlank() || other.length() > 100) { other.error = "请输入要对比的词或短语，最多 100 字" }
                else if(context.length() > 1000) { context.error = "语境请控制在 1000 字以内" }
                else tutor(StudyDirection.COMPARE.question(word.english, other.text.toString(), context.text.toString()))
            }.apply { id = ID_COMPARE })
        }
        savedAnswers(column)
        column.addView(ui.button("自由提问或查看全部对话", true) { tutor() })
        setContentView(ui.page("词汇学习", column))
    }

    private fun savedAnswers(column: LinearLayout) {
        val answers = store.messages("word:$wordId").filter { it.role == "assistant" && it.state == "done" }
        ui.card(column) { card ->
            card.addView(ui.text("已保存的讲解", 20f))
            if(answers.isEmpty()) card.addView(ui.text("还没有讲解。联网提问后，回答可在此离线回看；断网时也能先保存问题。", 13f, true))
            else {
                card.addView(ui.text("最近 ${answers.size} 条已保存回答，以下为最新两条摘要。AI 解释需结合语境核实。", 13f, true))
                for(answer in answers.takeLast(2)) {
                    card.addView(ui.text(answer.text.take(400) + if(answer.text.length > 400) "…" else "").apply { setTextIsSelectable(true) })
                }
                card.addView(ui.button("离线查看完整讲解") { tutor() })
            }
        }
    }

    companion object {
        const val ID_OTHER = 0x005a3001
        const val ID_CONTEXT = 0x005a3002
        const val ID_COMPARE = 0x005a3003
    }
}
