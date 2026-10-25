//! 学习主页与紧凑词库；复习在学习流程中展开，AI 是独立主入口。
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
    private var filter = "全部"
    private var reviewIndex = 0
    private var flipped = false
    private var remembered = 0
    private var reviewWords = emptyList<VocabularyEntry>()
    private val store by lazy { LearningStore.get(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = Views(this)
        page = (state?.getString("page") ?: intent.getStringExtra("page"))
            ?.takeIf { value -> value in listOf("today", "words", "review") } ?: "today"
        query = state?.getString("query").orEmpty()
        filter = state?.getString("filter") ?: "全部"
        reviewWords = state?.getLongArray("review_ids")?.toList()?.mapNotNull { store.word(it) }.orEmpty()
        if(state?.getLongArray("review_ids")?.size != reviewWords.size) reviewWords = emptyList()
        reviewIndex = state?.getInt("review_index", 0)?.coerceIn(0, reviewWords.size) ?: 0
        remembered = state?.getInt("remembered", 0)?.coerceIn(0, reviewWords.size) ?: 0
        flipped = state?.getBoolean("flipped") ?: false
    }

    override fun onResume() {
        super.onResume()
        if(reviewWords.any { store.word(it.id) == null }) { reviewWords = emptyList(); reviewIndex = 0; remembered = 0; flipped = false }
        render()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("page")?.takeIf { value -> value in listOf("today", "words", "review") }?.let { page = it }
        render()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page); out.putString("query", query); out.putString("filter", filter)
        out.putLongArray("review_ids", reviewWords.map { it.id }.toLongArray())
        out.putInt("review_index", reviewIndex); out.putInt("remembered", remembered); out.putBoolean("flipped", flipped)
        super.onSaveInstanceState(out)
    }

    private fun openTutor(word: VocabularyEntry? = null) {
        startActivity(Intent(this, TutorActivity::class.java).putExtra("word_id", word?.id ?: 0))
    }

    private fun render() {
        val root = ui.column()
        body = ui.column()
        if(page == "review") root.addView(ui.header("复习与练习").apply {
            setNavigationOnClickListener { page = "today"; render() }
        }) else root.addView(MainHeader.create(this, if(page == "today") "青简" else "我的词库"))
        when(page) {
            "words" -> vocabulary()
            "review" -> review()
            else -> today()
        }
        root.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        if(page != "review") root.addView(Navigation.create(this, page) { selected ->
            if(selected in listOf("ai","me")) Navigation.open(this, selected) else { page = selected; render() }
        })
        ui.insets(root); setContentView(root)
    }

    private fun today() {
        val words = store.words()
        val preferences = StudyPreferences(this)
        val records=org.qingjian.speech.learning.archive.Store(this).events()
        val recentMistake=records.asReversed().firstNotNullOfOrNull {e->if(e.getJSONObject("data").has("correct")&&!e.getJSONObject("data").optBoolean("correct"))store.word(e.optLong("word_id"))?.takeIf {it.meaning.isNotBlank()} else null}
        val available=recentMistake ?: store.word(preferences.lastWord)?.takeIf {it.meaning.isNotBlank()} ?: words.firstOrNull {it.meaning.isNotBlank()}
        body.addView(ui.caption("日常输入 · 拼写优先"));body.addView(ui.title("把常用词，\n写得更稳一点。"));body.addView(ui.caption("先巩固用过的词，再慢慢扩展。"))
        org.qingjian.speech.learning.daily.HomeCard.show(this, body)
        body.addView(ui.caption("按自己的节奏来 · 每天 ${preferences.minutes} 分钟"))
        body.addView(ui.menuRow("独立小测", "拼写、听写与语境填词，交卷后看结果", org.qingjian.speech.R.drawable.ic_book) {
            startActivity(Intent(this,org.qingjian.speech.learning.exam.ExamActivity::class.java))
        })
        ui.divider(body)
        body.addView(ui.menuRow("问问 AI 老师", "根据实际记录，决定先巩固还是扩展", org.qingjian.speech.R.drawable.ic_spark) {
            startActivity(Intent(this, TutorActivity::class.java).putExtra("home_tab", true))
        })
        ui.divider(body)
        val plan=org.qingjian.speech.learning.daily.PlanStore(this).current()
        body.addView(ui.menuRow(if(plan==null)"安排今天学习" else "继续今天学习",plan?.title ?: "让老师安排少量新词与复习",org.qingjian.speech.R.drawable.ic_study){startActivity(Intent(this,org.qingjian.speech.learning.daily.StudyActivity::class.java).putExtra("plan_id",plan?.id ?: 0L).putExtra("generate",plan==null))})
        body.addView(ui.menuRow("复习与练习","卡片自评、选择题和已有错题记录",org.qingjian.speech.R.drawable.ic_repeat){page="review";render()})
    }

    private fun openWord(word: VocabularyEntry) {
        startActivity(Intent(this, WordStudyActivity::class.java).putExtra("word_id", word.id))
    }

    private fun vocabulary() {
        body.addView(ui.caption("你遇到的词，值得慢慢学会。已支持双源检索。"))
        body.addView(ui.button("添加词汇或短语") { editWord() })
        val search = ui.input("搜索英文、中文笔记或词汇表", query)
        body.addView(search)
        val filters = ui.row()
        for(label in listOf("全部", "有笔记", "已自评")) filters.addView(ui.button(label) { filter = label; render() }.apply {
            isSelected = filter == label
            if(isSelected) background = ui.shape(ui.color(org.qingjian.speech.R.color.chip_background_active), 18)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(5) })
        body.addView(filters)

        val warningCaption = ui.caption("").apply { visibility = android.view.View.GONE }
        body.addView(warningCaption)

        val list = ui.column(); body.addView(list)
        fun populate() {
            list.removeAllViews()
            val matches = store.words(query).filter { when(filter) { "有笔记" -> it.meaning.isNotBlank(); "已自评" -> it.reviewed > 0; else -> true } }
            if(matches.isNotEmpty()) {
                if (query.isNotBlank()) list.addView(ui.caption("已在词库中 (${matches.size})"))
                matches.forEach { word ->
                    list.addView(ui.wordRow(word.english, word.meaning.ifBlank { "还没有中文笔记" }) { openWord(word) }.apply {
                        contentDescription = "学习这个词：${word.english}"
                    })
                    ui.divider(list)
                }
            } else if (query.isBlank() || filter != "全部") {
                list.addView(ui.text("没有找到词汇", muted = true))
            }

            val trimmed = query.trim()
            if (trimmed.isNotEmpty() && filter == "全部") {
                val table = org.qingjian.speech.learning.glossary.GlossaryStore.bundled(this, "en")
                if (table == null) {
                    warningCaption.text = "还没下载词汇表，仅展示本地词库结果"
                    warningCaption.visibility = android.view.View.VISIBLE
                } else {
                    warningCaption.visibility = android.view.View.GONE
                    val lookup = org.qingjian.speech.learning.glossary.GlossaryLookup(
                        table,
                        org.qingjian.speech.learning.glossary.GlossaryStore.load(this, "en")
                    )
                    val staticResults = lookup.search(trimmed, 15)
                    val existing = store.allWords().map { org.qingjian.speech.learning.quiz.Question.normalize(it.english) }.toSet()
                    val unadded = staticResults.filter { org.qingjian.speech.learning.quiz.Question.normalize(it.second.text) !in existing }
                    if (unadded.isNotEmpty()) {
                        list.addView(ui.caption("词汇表推荐（未添加）"))
                        for ((chineseKey, sense) in unadded) {
                            val pos = sense.partOfSpeech?.let { "[$it] " }.orEmpty()
                            ui.card(list) { card ->
                                card.addView(ui.title(sense.text))
                                card.addView(ui.text("$pos$chineseKey", 14f))
                                card.addView(ui.button("加入词库") {
                                    val newId = store.addWord(sense.text, "static-table")
                                    store.setMeaning(newId, "$pos$chineseKey".trim())
                                    android.widget.Toast.makeText(this@LearningActivity, "已加入词库: ${sense.text}", android.widget.Toast.LENGTH_SHORT).show()
                                    populate()
                                })
                            }
                            ui.divider(list)
                        }
                    } else if (matches.isEmpty()) {
                        list.addView(ui.text("没有找到词汇", muted = true))
                    }
                }
            } else {
                warningCaption.visibility = android.view.View.GONE
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); populate() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        populate()
        body.addView(ui.caption("词汇、中文笔记与已保存讲解，离线也能看。"))
    }

    private fun editWord(word: VocabularyEntry? = null) { WordEditor.show(this, word) { render() } }

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
