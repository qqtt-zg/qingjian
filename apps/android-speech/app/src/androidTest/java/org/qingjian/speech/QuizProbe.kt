//! 真实原生测验页验证：答案先隐藏，提交后反馈，重建与错题重测保持正确记录。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.HistoryActivity
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.QuizActivity

object QuizProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val practice = PracticeStore(app)
        val ui = ProbeUi(test)
        store.setMeaning(store.addWord("control"), "控制")
        fun launch(kind: Kind, wrongOnly: Boolean = false): QuizActivity = test.startActivitySync(
            Intent(app, QuizActivity::class.java).putExtra("kind", kind.name).putExtra("wrong_only", wrongOnly)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as QuizActivity
        var quiz = launch(Kind.SPELLING)
        ui.await("spelling visible") { quiz.hasWindowFocus() }
        ui.main {
            check(!ui.text(quiz.window.decorView).contains("control"))
            quiz.findViewById<EditText>(QuizActivity.ID_ANSWER).setText("  CONTROL ")
            quiz.findViewById<Button>(QuizActivity.ID_SUBMIT).performClick()
            check(ui.text(quiz.window.decorView).contains("答对了"))
        }
        check(practice.summary(Kind.SPELLING) == (1 to 1))
        val monitor = test.addMonitor(QuizActivity::class.java.name, null, false)
        ui.main { quiz.recreate() }
        quiz = test.waitForMonitorWithTimeout(monitor, 10000) as? QuizActivity ?: error("quiz recreation failed")
        test.removeMonitor(monitor)
        ui.await("recreated quiz") { quiz.hasWindowFocus() }
        ui.main { check(ui.text(quiz.window.decorView).contains("答对了")) }
        check(practice.summary(Kind.SPELLING) == (1 to 1))
        ui.click(quiz, "查看本轮结果")
        ui.main { check(ui.text(quiz.window.decorView).contains("答对 1 / 1")); quiz.finish() }
        store.setMeaning(store.addWord("water"), "水")
        quiz = launch(Kind.CHOICE)
        ui.await("choice visible") { quiz.hasWindowFocus() }
        var current = store.words().first()
        ui.main {
            val text = ui.text(quiz.window.decorView)
            current = store.words().first { text.contains(it.english) }
        }
        val wrong = store.words().first { it.meaning != current.meaning }.meaning
        ui.click(quiz, wrong)
        ui.main { check(ui.text(quiz.window.decorView).contains("本题答案：")); quiz.finish() }
        check(practice.summary(Kind.CHOICE) == (1 to 0))
        val history = test.startActivitySync(Intent(app, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("history visible") { history.hasWindowFocus() }
        ui.main { check(ui.text(history.window.decorView).contains(current.english)); history.finish() }
        quiz = launch(Kind.CHOICE, true)
        ui.await("retry wrong only") { quiz.hasWindowFocus() }
        ui.main { check(ui.text(quiz.window.decorView).contains(current.english)) }
        ui.click(quiz, current.meaning)
        check(practice.summary(Kind.CHOICE) == (2 to 1) && practice.mistakes().isEmpty())
        ui.click(quiz, "查看本轮结果")
        ui.click(quiz, "再来一轮")
        ui.main { check(ui.text(quiz.window.decorView).contains("目前没有可重测")) }
        ui.main { quiz.finish() }
        return "PASS quiz UI: hidden answer, spelling normalization, persisted feedback/recreation without duplicate grade, per-kind summary, wrong-answer history and focused retry"
    }
}
