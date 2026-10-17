//! 按题型展示成绩与待巩固错题，答对后的旧错误仍保留在历史统计中。
package org.qingjian.speech.learning.quiz

import android.app.Activity
import android.content.Intent
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.practice.PracticeStore

class HistoryActivity : Activity() {
    override fun onResume() {
        super.onResume()
        val ui = Views(this)
        val practice = PracticeStore(this)
        val column = ui.column()
        column.addView(ui.text("累计结果按题型分别统计；错题列表显示每个词该题型最近一次仍答错的记录，最多 50 条。", 13f, true))
        for(kind in Kind.entries) {
            val (total, correct) = practice.summary(kind)
            column.addView(ui.text("${kind.label}：累计作答 $total 次，答对 $correct 次"))
        }
        val mistakes = practice.mistakes()
        if(mistakes.isEmpty()) column.addView(ui.text("暂时没有待巩固错题。", 20f))
        else {
            for(kind in Kind.entries) column.addView(ui.button("重测${kind.label}错题", true) {
                startActivity(Intent(this, QuizActivity::class.java).putExtra("kind", kind.name).putExtra("wrong_only", true))
            }.apply { isEnabled = mistakes.any { it.kind == kind } })
            for(attempt in mistakes) ui.card(column) { card ->
                card.addView(ui.text(attempt.english, 20f))
                card.addView(ui.text("${attempt.kind.label} · ${attempt.meaning}", muted = true))
                card.addView(ui.text("上次答案：${attempt.answer}"))
                card.addView(ui.button("学习这个词") { startActivity(Intent(this, WordStudyActivity::class.java).putExtra("word_id", attempt.wordId)) })
            }
        }
        setContentView(ui.page("错题本与记录", column))
    }
}
