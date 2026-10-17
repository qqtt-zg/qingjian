//! 学习优先的四入口首页；朗读与应用配置集中在统一设置页。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import org.qingjian.speech.MainActivity
import org.qingjian.speech.ReceiverService
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.QuizActivity
import org.qingjian.speech.learning.quiz.HistoryActivity
import org.qingjian.speech.learning.oral.OralActivity
import org.qingjian.speech.learning.practice.PracticeStore

class LearningActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var body: LinearLayout
    private var page = "today"
    private var query = ""
    private var reviewIndex = 0
    private var flipped = false
    private var remembered = 0
    private var reviewWords = emptyList<VocabularyEntry>()
    private val store by lazy { LearningStore.get(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = Views(this)
        page = (state?.getString("page") ?: intent.getStringExtra("page"))
            ?.takeIf { value -> Navigation.pages.any { it.first == value && value != "settings" } } ?: "today"
        query = state?.getString("query").orEmpty()
    }

    override fun onResume() {
        super.onResume()
        if(reviewWords.any { store.word(it.id) == null }) { reviewWords = emptyList(); reviewIndex = 0; remembered = 0; flipped = false }
        render()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("page")?.takeIf { value -> Navigation.pages.any { it.first == value && value != "settings" } }?.let { page = it }
        render()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("page", page); out.putString("query", query); super.onSaveInstanceState(out) }

    private fun openTutor(word: VocabularyEntry? = null) {
        startActivity(Intent(this, TutorActivity::class.java).putExtra("word_id", word?.id ?: 0))
    }

    private fun render() {
        val root = ui.column()
        body = ui.column()
        when(page) {
            "words" -> vocabulary()
            "review" -> review()
            else -> today()
        }
        root.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = Navigation.create(this, page) { selected ->
            if(selected == "settings") startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            else { page = selected; render() }
        }
        root.addView(nav); ui.insets(root); setContentView(root)
    }

    private fun today() {
        val words = store.words()
        body.addView(ui.title("今天，再认识几个词"))
        val speech = getSharedPreferences("speech", 0).getString("status", "尚未连接电脑")
        body.addView(ui.text("朗读连接：$speech\n学习资料与历史回答保存在手机，离线可查看。", 13f, true))
        ui.card(body) { card ->
            card.addView(ui.text("我的词汇", 20f))
            card.addView(ui.text(if(words.isEmpty()) "还没有学习条目" else "最近收录 ${words.size} 个词／短语"))
            card.addView(ui.text("电脑点击小喇叭后自动收录，也可以在词汇页手动添加。收到请求与完整播放分别记录。", 13f, true))
            card.addView(ui.button("打开词汇本", true) { page = "words"; render() })
        }
        ui.card(body) { card ->
            card.addView(ui.text("今天遇到了什么疑问？", 20f))
            card.addView(ui.text("词性、选词、搭配、例句，都可以继续追问。", muted = true))
            card.addView(ui.button("问一问 AI 老师", true) { openTutor() })
        }
        if(words.isNotEmpty()) {
            body.addView(ui.text("最近的学习条目", 18f))
            words.take(3).forEach { wordCard(body, it) }
        }
    }

    private fun vocabulary() {
        body.addView(ui.title("词汇本"))
        body.addView(ui.text("保存最近 500 个条目的列表；点词汇补充中文笔记、朗读或提问。", 13f, true))
        body.addView(ui.button("添加词汇或短语") { editWord() })
        val search = ui.input("搜索英文或中文笔记", query)
        body.addView(search)
        val list = ui.column(); body.addView(list)
        fun populate() {
            list.removeAllViews()
            val matches = store.words(query)
            if(matches.isEmpty()) list.addView(ui.text("没有找到词汇"))
            matches.forEach { wordCard(list, it) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); populate() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        populate()
    }

    private fun wordCard(parent: LinearLayout, word: VocabularyEntry) {
        ui.card(parent) { card ->
            card.addView(ui.text(word.english, 20f))
            card.addView(ui.text(word.meaning.ifEmpty { "中文笔记尚未填写" }, muted = true))
            card.addView(ui.text("收到 ${word.received} 次 · 完整播放 ${word.played} 次 · 自评复习 ${word.reviewed} 次", 12f, true))
            card.addView(ui.button("朗读") { speak(word.english) })
            card.addView(ui.button("学习这个词", true) {
                startActivity(Intent(this, WordStudyActivity::class.java).putExtra("word_id", word.id))
            })
            card.addView(ui.button("编辑中文笔记") { editWord(word) })
        }
    }

    private fun editWord(word: VocabularyEntry? = null) {
        val fields = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val english = ui.input("英文单词或短语", word?.english.orEmpty())
        english.isEnabled = word == null
        val meaning = ui.input("中文笔记（可留空）", word?.meaning.orEmpty(), true)
        fields.addView(english); fields.addView(meaning)
        val dialog = AlertDialog.Builder(this).setTitle(if(word == null) "添加学习条目" else "编辑中文笔记")
            .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = english.text.toString().trim()
                if(value.isEmpty() || value.length > 500) { english.error = "请输入 1～500 字的英文"; return@setOnClickListener }
                if(meaning.length() > 1000) { meaning.error = "中文笔记请控制在 1000 字以内"; return@setOnClickListener }
                val id = word?.id ?: store.addWord(value)
                store.setMeaning(id, meaning.text.toString()); dialog.dismiss(); render()
            }
        }
        dialog.show()
    }

    private fun speak(english: String) {
        startForegroundService(Intent(this, ReceiverService::class.java).setAction("word").putExtra("text", english))
    }

    private fun review() {
        body.addView(ui.title("复习"))
        body.addView(ui.text("卡片用于自评，测验与跟读分别记录。选择适合当前目标的练习方式。", 13f, true))
        cardReview()
        practiceTools()
        body.addView(ui.text("卡片自评、笔记测验与口语自评分开记录。自动发音评估和定时总结尚未接入。", 13f, true))
    }

    private fun practiceTools() {
        ui.card(body) { card ->
            card.addView(ui.text("离线测验", 20f))
            card.addView(ui.text("根据你的中文笔记出题，每轮最多五题。选择题与拼写分别记录成绩，答错可到错题本重测。", 13f, true))
            for(kind in Kind.entries) {
                val (total, correct) = PracticeStore(this).summary(kind)
                card.addView(ui.button("${kind.label} · $correct / $total") {
                    startActivity(Intent(this, QuizActivity::class.java).putExtra("kind", kind.name))
                })
            }
            card.addView(ui.button("错题本与记录") { startActivity(Intent(this, HistoryActivity::class.java)) })
        }
        ui.card(body) { card ->
            card.addView(ui.text("录音跟读", 20f))
            card.addView(ui.text("先听示范，再录音回放与自评。当前未接入自动发音评分。", 13f, true))
            card.addView(ui.button("选择词汇跟读") { startActivity(Intent(this, OralActivity::class.java)) })
        }
    }

    private fun cardReview() {
        if(reviewWords.isEmpty()) {
            val available = store.words().filter { it.meaning.isNotBlank() }
            ui.card(body) { card ->
                card.addView(ui.text("卡片复习", 20f))
                card.addView(ui.text(if(available.isEmpty()) "先给词汇填写中文笔记，就能离线复习。" else "从最近的词中选取最多 5 个，逐个回忆。"))
                card.addView(ui.button("开始复习", true) { reviewWords = available.take(5); reviewIndex = 0; remembered = 0; flipped = false; render() }.apply { isEnabled = available.isNotEmpty() })
            }
        } else if(reviewIndex >= reviewWords.size) {
            body.addView(ui.text("本轮完成 ${reviewWords.size} 个词\n自评记住 $remembered 个，待巩固 ${reviewWords.size-remembered} 个。", 20f))
            body.addView(ui.button("结束本轮", true) { reviewWords = emptyList(); render() })
        } else {
            val word = reviewWords[reviewIndex]
            body.addView(ui.text("${reviewIndex+1} / ${reviewWords.size}", muted = true))
            ui.card(body) { card ->
                card.addView(ui.title(word.english))
                card.addView(ui.button("听发音") { speak(word.english) })
                if(flipped) {
                    card.addView(ui.text(word.meaning, 20f))
                    for((label, known) in listOf("还需巩固" to false, "这次记住了" to true)) card.addView(ui.button(label, known) {
                        store.reviewed(word.id); if(known) remembered++; reviewIndex++; flipped = false; render()
                    })
                    card.addView(ui.button("问一问这个词") { openTutor(word) })
                    card.addView(ui.button("录音跟读") { startActivity(Intent(this, OralActivity::class.java).putExtra("word_id", word.id)) })
                } else card.addView(ui.button("查看笔记", true) { flipped = true; render() })
            }
        }
    }

}
