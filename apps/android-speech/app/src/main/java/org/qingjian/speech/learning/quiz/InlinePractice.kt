//! AI 工具直接呈现真实题目；提交和重建复用固定题号，反馈依据实际作答。
package org.qingjian.speech.learning.quiz

import android.app.Activity
import android.widget.LinearLayout
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.ai.tools.Action
import org.qingjian.speech.learning.ai.tools.ActionStore
import org.qingjian.speech.learning.practice.PracticeStore

class InlinePractice(private val activity: Activity, private val speech: PreviewSpeech, private val changed: () -> Unit, private val ask: (String) -> Unit) {
    private val ui = Views(activity)
    private val actions = ActionStore(activity)
    private val practice = PracticeStore(activity)

    fun render(parent: LinearLayout, action: Action, enabled: Boolean) {
        val questions = runCatching { actions.practice(action.id) }.getOrElse {
            parent.addView(ui.caption("这轮练习缺少可用词条或笔记，请重新准备。")); return
        }
        val index = action.payload.optInt("index").coerceIn(0, questions.size)
        ui.card(parent) { card ->
            if(index >= questions.size) {
                val answers = questions.mapNotNull { practice.answer(it.key) }
                card.addView(ui.title("本轮完成"))
                card.addView(ui.text("答对 ${answers.count { it.correct }} / ${questions.size}；练过一次不代表已掌握。"))
                card.addView(ui.caption("结果来自本机实际作答；没有自动发音评分。"))
                return@card
            }
            val question = questions[index]
            val result = practice.answer(question.key)
            card.addView(ui.caption("${question.kind.label} · ${index + 1} / ${questions.size}"))
            card.addView(ui.text(when(question.kind) {
                Kind.CHOICE -> question.english
                Kind.CLOZE -> question.prompt
                else -> question.meaning
            }, 21f))
            if(question.kind == Kind.SPELLING && result == null) {
                val input = ui.input("填写英文词或短语")
                card.addView(input)
                card.addView(ui.button("提交答案", true) { save(question, input.text.toString()) }.apply { isEnabled = enabled })
            } else if(question.kind != Kind.SPELLING) {
                OptionsView(activity, speech).render(card, question, result, enabled) { save(question, it) }
            }
            if(result != null) {
                card.addView(ui.text(if(result.correct) "这题答对了" else "这题需要再巩固", 17f))
                card.addView(ui.text("本题答案：${if(question.kind == Kind.CHOICE) question.meaning else question.english}", 14f))
                card.addView(ui.caption("本题已完成；只记录本次作答，不代表掌握。"))
                card.addView(ui.button(if(index + 1 < questions.size) "下一题" else "查看本轮小结") { speech.stop(); actions.advancePractice(action.id); changed() }.apply { isEnabled = enabled })
                card.addView(ui.button(if(result.correct && question.kind != Kind.CLOZE) "继续练搭配" else "再巩固一下") {
                    speech.stop()
                    ask(if(result.correct && question.kind != Kind.CLOZE) "请给我出一道 ${question.english} 的 CLOZE 搭配练习。如缺少包含原词的例句，先整理一张词卡。" else "请再给我出一道 ${question.english} 的 ${question.kind.name} 练习。")
                }.apply { isEnabled = enabled })
            }
        }
    }

    private fun save(question: Question, text: String) {
        speech.stop()
        runCatching { practice.submit(question, text) }.onFailure {
            android.widget.Toast.makeText(activity, "答案未保存，请检查内容或重新准备练习", android.widget.Toast.LENGTH_LONG).show()
        }
        changed()
    }
}
