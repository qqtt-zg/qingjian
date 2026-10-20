//! 离线笔记测验；每题提交后才揭示答案，页面重建不重复计分。
package org.qingjian.speech.learning.quiz

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import org.json.JSONArray
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.LearningActivity
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.oral.OralActivity
import org.qingjian.speech.learning.practice.PracticeStore

class QuizActivity : Activity() {
    private lateinit var ui: Views
    private val store by lazy { LearningStore.get(this) }
    private val practice by lazy { PracticeStore(this) }
    private var kind = Kind.CHOICE
    private var questions = emptyList<Question>()
    private var index = 0
    private var input: EditText? = null
    private var draft = ""
    private var notice = ""
    private val speech by lazy { PreviewSpeech(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = Views(this)
        kind = runCatching { Kind.valueOf(intent.getStringExtra("kind").orEmpty()) }.getOrDefault(Kind.CHOICE)
        if(state == null) newRound() else {
            runCatching {
                val raw = state.getString("questions").orEmpty()
                require(raw.length <= 100000)
                val values = JSONArray(raw); require(values.length() <= 5)
                questions = (0 until values.length()).map { Question.from(values.getJSONObject(it)) }
                index = state.getInt("index").coerceIn(0, questions.size)
                draft = state.getString("draft").orEmpty().take(500)
            }.onFailure { notice = "本轮已中断，请重新开始测验。" }
        }
    }

    override fun onResume() { super.onResume(); render() }
    override fun onPause() { speech.close(); super.onPause() }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("questions", JSONArray(questions.map { it.json() }).toString())
        out.putInt("index", index); out.putString("draft", input?.text?.toString() ?: draft)
        super.onSaveInstanceState(out)
    }

    private fun newRound() {
        val actionId = intent.getLongExtra("learning_action", 0)
        if(actionId > 0) {
            questions = runCatching { org.qingjian.speech.learning.ai.tools.ActionStore(this).practice(actionId) }.getOrElse {
                notice = "练习资料不适用，请补充中文笔记或重新准备。"; emptyList()
            }
            index = 0; input = null; draft = ""; return
        }
        val selected = if(intent.getBooleanExtra("wrong_only", false)) practice.mistakes().filter { it.kind == kind }.map { it.wordId }.toSet() else intent.getLongExtra("word_id", 0).takeIf { it > 0 }?.let { setOf(it) }
        val words = store.words()
        val examples = if(kind == Kind.CLOZE) words.filter { selected == null || it.id in selected }.mapNotNull {
            org.qingjian.speech.learning.ai.tools.ActionStore(this).approvedCard(it.id)?.optString("example")?.let { example -> it.id to example }
        }.toMap() else emptyMap()
        questions = Builder.build(words, kind, selected, examples = examples)
        index = 0; input = null; draft = ""; notice = ""
    }

    private fun submit(question: Question, text: String) {
        speech.stop()
        if(text.isBlank() || text.length > if(question.kind == Kind.SPELLING) 500 else 1000) { input?.error = "请填写答案，最多 500 字"; return }
        try { practice.submit(question, text); notice = ""; render() }
        catch(error: IllegalArgumentException) { notice = error.message ?: "无法保存答案，请重新开始"; render() }
    }

    private fun render() {
        speech.clearBindings()
        draft = input?.text?.toString() ?: draft; input = null
        val column = ui.column()
        column.addView(ui.text("按你保存的中文笔记出题；成绩仅表示本次作答，不代表已掌握词汇。", 13f, true))
        if(notice.isNotEmpty()) column.addView(ui.text(notice, muted = true))
        if(questions.isEmpty()) {
            val empty = if(kind == Kind.CLOZE) "搭配填空需要词卡中已有包含原词的英文例句，以及至少两个有中文笔记的词。可先向 AI 请求搭配例句。"
                else if(intent.getBooleanExtra("wrong_only", false)) "目前没有可重测的本类错题；笔记重复的拼写条目会被跳过。"
                else if(kind == Kind.CHOICE) "词义选择至少需要两条不同的中文笔记。"
                else if(kind == Kind.ENGLISH_CHOICE) "英文选词需要至少两个有中文笔记的不同词，目标词的笔记应能区分。"
                else "先补充中文笔记。多个词的笔记相同时会跳过，避免拼写题答案不唯一。"
            column.addView(ui.text(empty, 18f))
            column.addView(ui.button("重新准备题目", true) { newRound(); render() })
            column.addView(ui.button("打开词汇本") {
                startActivity(Intent(this, LearningActivity::class.java).putExtra("page", "words")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            })
        } else if(index >= questions.size) {
            val answers = questions.mapNotNull { practice.answer(it.key) }
            column.addView(ui.title("本轮完成"))
            column.addView(ui.text("${kind.label}：答对 ${answers.count { it.correct }} / ${questions.size}", 22f))
            column.addView(ui.text("错题已保存，后续同一词同类题答对后会从待巩固列表移除。", muted = true))
            column.addView(ui.button(if(intent.getLongExtra("learning_action", 0) > 0) "结束本轮练习" else "再来一轮", true) {
                if(intent.getLongExtra("learning_action", 0) > 0) finish() else { newRound(); render() }
            })
            column.addView(ui.button("查看错题本") { startActivity(Intent(this, HistoryActivity::class.java)) })
        } else {
            val question = questions[index]
            val result = practice.answer(question.key)
            column.addView(ui.text("${index + 1} / ${questions.size}", muted = true))
            ui.card(column) { card ->
                card.addView(ui.text(when(kind) { Kind.CHOICE -> "选择你笔记中对应的意思"; Kind.ENGLISH_CHOICE -> "选择对应的英文词或短语"; Kind.CLOZE -> "选择原词卡例句中的搭配"; else -> "根据这条中文笔记拼写英文" }, 18f))
                card.addView(ui.title(when(kind) { Kind.CHOICE -> question.english; Kind.CLOZE -> question.prompt; else -> question.meaning }))
                if(kind != Kind.SPELLING) OptionsView(this, speech).render(card, question, result) { submit(question, it) }
                if(result == null) {
                    if(kind == Kind.SPELLING) {
                        input = ui.input("填写单词或短语", draft).apply {
                            id = ID_ANSWER; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        card.addView(input)
                        card.addView(ui.button("提交答案", true) { submit(question, input!!.text.toString()) }.apply { id = ID_SUBMIT })
                    }
                } else {
                    card.addView(ui.text(if(result.correct) "答对了" else "与本题笔记中的答案不一致", 20f))
                    card.addView(ui.text("你的答案：${result.answer}\n本题答案：${if(kind == Kind.CHOICE) result.meaning else result.english}"))
                    card.addView(ui.text("${result.english} · ${result.meaning}", muted = true))
                    card.addView(ui.button(if(index + 1 == questions.size) "查看本轮结果" else "下一题", true) { index++; input = null; draft = ""; render() })
                    card.addView(ui.button("录音跟读这个词") { startActivity(Intent(this, OralActivity::class.java).putExtra("word_id", question.wordId)) })
                    card.addView(ui.button("问一问为什么") { startActivity(Intent(this, TutorActivity::class.java).putExtra("word_id", question.wordId)) })
                    card.addView(ui.button(if(result.correct) "继续练搭配" else "再巩固一下") {
                        if(result.correct && kind != Kind.CLOZE) {
                            startActivity(Intent(this, QuizActivity::class.java).putExtra("word_id", question.wordId).putExtra("kind", Kind.CLOZE.name))
                        } else {
                            intent.removeExtra("learning_action"); intent.putExtra("word_id", question.wordId); newRound(); render()
                        }
                    })
                }
            }
        }
        setContentView(ui.page(kind.label, column))
    }

    companion object {
        const val ID_ANSWER = 0x005a4001
        const val ID_SUBMIT = 0x005a4002
    }
}
