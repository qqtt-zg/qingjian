//! AI 专属首页与上下文对话；提问框固定在底部，问题先保存且只在明确发送后请求。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.qingjian.speech.R
import org.qingjian.speech.learning.ai.Session
import org.qingjian.speech.learning.ai.SettingsActivity
import org.qingjian.speech.learning.ai.memory.Conversations
import org.qingjian.speech.learning.ai.memory.ConversationMenu

class TutorActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var session: Session
    private lateinit var input: EditText
    private var draft = ""
    private var homeTab = false
    private var showHistory = false
    private var expandedMessages = false
    private val speech by lazy { org.qingjian.speech.learning.quiz.PreviewSpeech(this) }
    private var contextExpanded = false
    private var resumed = false
    private var drawer: android.app.Dialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); ui = Views(this)
        val wordId = intent.getLongExtra("word_id", 0)
        val conversations = Conversations(this)
        session = (lastNonConfigurationInstance as? Session) ?: Session(applicationContext, wordId,
            (state?.getString("topic") ?: intent.getStringExtra("topic"))?.takeIf { conversations.get(it)?.wordId == wordId } ?: conversations.selected(wordId))
        conversations.ensure(session.topic, wordId)
        conversations.select(session.topic)
        draft = (state?.getString("draft") ?: intent.getStringExtra("question") ?: conversations.draft(session.topic)).take(4000)
        homeTab = intent.getBooleanExtra("home_tab", false)
        showHistory = state?.getBoolean("history") ?: false
        expandedMessages = state?.getBoolean("expanded") ?: false
        contextExpanded = state?.getBoolean("context_expanded") ?: false
        render()
    }

    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value); setIntent(value)
        val nextWord = value.getLongExtra("word_id", 0)
        val conversations = Conversations(this)
        conversations.saveDraft(session.topic, input.text.toString())
        val nextTopic = value.getStringExtra("topic")?.takeIf { conversations.get(it)?.wordId == nextWord }
            ?: if(nextWord == session.wordId) session.topic else conversations.selected(nextWord)
        if(nextTopic != session.topic || nextWord != session.wordId) {
            session.close(); session = Session(applicationContext, nextWord, nextTopic)
            conversations.ensure(session.topic, nextWord); conversations.select(session.topic); expandedMessages = false
        }
        draft = (value.getStringExtra("question") ?: conversations.draft(session.topic)).take(4000)
        homeTab = value.getBooleanExtra("home_tab", false)
        session.changed = { render() }; input.setText(draft); render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        val conversations = Conversations(this)
        if(conversations.get(session.topic) == null) {
            val wordId = session.wordId.takeIf { LearningStore.get(this).word(it) != null } ?: 0
            session.close(); session = Session(applicationContext, wordId)
            conversations.ensure(session.topic, wordId); conversations.select(session.topic)
            draft = ""; input.setText(""); expandedMessages = false
        }
        session.changed = { render() }; render()
    }
    override fun onPause() { resumed = false; drawer?.dismiss(); drawer = null; draft = input.text.toString(); Conversations(this).saveDraft(session.topic, draft); session.changed = null; speech.close(); super.onPause() }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("draft", input.text.toString()); out.putString("topic", session.topic); out.putBoolean("history", showHistory); out.putBoolean("expanded", expandedMessages); super.onSaveInstanceState(out)
        out.putBoolean("context_expanded", contextExpanded)
    }
    override fun onDestroy() { if(!isChangingConfigurations) session.close(); super.onDestroy() }

    private fun fill(question: String) { draft = question; input.setText(question); input.setSelection(input.length()) }
    private fun ask(question: String, retry: Long? = null) { speech.stop(); session.send(question, retry) }

    private fun openConversation(topic: String) {
        if(session.busy) return
        speech.stop()
        val conversations = Conversations(this)
        conversations.saveDraft(session.topic, input.text.toString())
        val wordId = session.wordId
        session.close(); session = Session(applicationContext, wordId, topic); conversations.select(topic)
        draft = conversations.draft(topic); input.setText(draft); expandedMessages = false
        session.changed = { render() }; render()
    }

    private fun render() {
        speech.clearBindings()
        if(::input.isInitialized) draft = input.text.toString()
        val store = LearningStore.get(this)
        val word = store.word(session.wordId)
        val root = ui.column()
        val conversations = ConversationMenu(this, ui, session.topic, session.wordId, session.busy) { openConversation(it) }
        root.addView(org.qingjian.speech.learning.ai.TutorHeader.create(this, homeTab) { drawer?.dismiss(); drawer = conversations.show() })
        val column = ui.column()
        if(homeTab)column.addView(ui.button("学习档案与全量分析") {startActivity(Intent(this,org.qingjian.speech.learning.archive.ArchiveActivity::class.java).putExtra("page","memory"))}.apply {textSize=12f;setBackgroundColor(android.graphics.Color.TRANSPARENT)})
        if(word != null) column.addView(ui.caption("当前学习 · ${word.english} · ${word.meaning.ifBlank { "未填写笔记" }}"))
        val messages = store.messages(session.topic)
        if(session.contextDescription.isNotBlank()) {
            val details = ui.text(session.contextDescription, 12f, true).apply { visibility = if(contextExpanded) View.VISIBLE else View.GONE }
            column.addView(ui.button("本次参考 · 查看上下文") { contextExpanded = !contextExpanded; details.visibility = if(contextExpanded) View.VISIBLE else View.GONE })
            column.addView(details)
        }
        val actionsView = org.qingjian.speech.learning.ai.tools.ActionsView(this, speech, { render() }) { ask(it) }
        var focusAction: View? = null
        if(messages.isEmpty()) {
            column.addView(ui.title("有不懂的，\n我们慢慢讲。"))
            column.addView(ui.text("从一个词、一句话开始。\n用中文解释，每次只练一点。", 14f, true))
            val preferences = StudyPreferences(this)
            column.addView(ui.action("今天学哪几个词？", "按我的基础，推荐少量常用词", R.drawable.ic_book) {
                fill("请根据我的学习偏好，推荐 ${preferences.newWords} 个日常常用词。每次只教一个，给常用意思、一个搭配和一句简单例句，再出一道题等我回答。")
            })
            column.addView(ui.action("两个词，该用哪一个？", "放进具体句子，更容易弄明白", R.drawable.ic_spark) {
                fill(if(word == null) "请帮我辨析两个英文词。先问我要比较哪两个词，以及想表达的句子。" else "请帮我辨析 ${word.english} 和另一个词。先问我要对比的词及具体语境。")
            })
            column.addView(ui.action("这句话，英语怎么说？", "从你的中文表达开始练习", R.drawable.ic_mic) {
                fill("请帮我把一句日常中文说成简单自然的英文。先询问我想表达什么，再解释常用搭配。")
            })
            word?.let { column.addView(ui.button("填入问题：这个词怎么用？") { fill(StudyDirection.BASICS.question(it.english)) }) }
        } else {
            val shown = if(expandedMessages) messages else messages.takeLast(20)
            if(messages.size > shown.size) column.addView(ui.button("查看更早的对话") { expandedMessages = true; render() })
            for(entry in shown) {
                ui.card(column) { card ->
                    if(entry.role == "user") card.background = ui.shape(ui.color(R.color.chip_background), 16)
                    card.addView(ui.caption(if(entry.role == "user") "我" else "青简老师"))
                    card.addView(ui.text(entry.text).apply { setTextIsSelectable(true) })
                    if(entry.state != "done") card.addView(ui.caption("尚未取得回答 · 问题已本地保存"))
                }
                val actionQuestion = if(entry.role == "assistant") entry.replyTo else entry.id.takeIf { shown.none { item -> item.replyTo == entry.id } }
                val playbackQuestion = if(entry.role == "assistant" && session.canDispatchPlayback) session.lastQuestionId else 0L
                actionQuestion?.let { id -> actionsView.render(column, session.topic, !session.busy && resumed, id, playbackQuestion)?.let { focusAction = it } }
            }
        }
        if(session.busy) column.addView(ProgressBar(this).apply { isIndeterminate = true; contentDescription = "AI 正在回答" }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
        column.addView(ui.caption(session.notice.ifEmpty { "选择方向后先准备问题，点发送才会请求 AI。" }).apply {
            id = ID_STATUS; accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        if(session.busy) column.addView(ui.button("取消本次请求") { session.cancel() })
        if(!session.busy && messages.isNotEmpty()) {
            val latestUser = messages.lastOrNull { it.role == "user" }?.id
            val practiceAction = org.qingjian.speech.learning.ai.tools.ActionStore(this).forTopic(session.topic)
                .filter { it.kind == "prepare_practice" && it.state == "applied" && it.questionId == latestUser }.maxByOrNull { it.id }
            val values = practiceAction?.payload?.optJSONArray("questions")
            val lastQuestion = values?.takeIf { it.length() > 0 }?.let {
                val position = (practiceAction?.payload?.optInt("index") ?: 0).coerceIn(0, it.length() - 1)
                runCatching { org.qingjian.speech.learning.quiz.Question.from(it.getJSONObject(position)) }.getOrNull()
            }
            val roundFinished = values != null && practiceAction != null && practiceAction.payload.optInt("index") >= values.length()
            val answer = if(roundFinished) null else lastQuestion?.let { org.qingjian.speech.learning.practice.PracticeStore(this).answer(it.key) }
            val latestCard = org.qingjian.speech.learning.ai.tools.ActionStore(this).forTopic(session.topic)
                .filter { it.kind == "prepare_word_card" && it.state == "applied" }.maxByOrNull { it.id }
            val pronunciationWord = word?.english ?: lastQuestion?.english ?: latestCard?.let { store.word(it.wordId)?.english }
            val nextLabel = when {
                answer == null -> "下一步"
                !answer.correct -> "再巩固一下"
                answer.kind == org.qingjian.speech.learning.quiz.Kind.CLOZE -> if(values != null && practiceAction != null && practiceAction.payload.optInt("index") < values.length() - 1) "下一题" else "先到这里"
                else -> "继续练搭配"
            }
            val next = ui.row()
            next.addView(ui.button("练一题") {
                val selected = word?.english.orEmpty()
                ask(if(selected.isBlank()) "请根据刚才的学习内容，从本机词库准备一道 ENGLISH_CHOICE 英文选词练习，不提前透露答案。" else "请给我出一道 $selected 的 ENGLISH_CHOICE 练习，不提前透露答案。")
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if(pronunciationWord != null) next.addView(ui.button("听发音") { ask("请朗读 $pronunciationWord。") }.apply { tooltipText = "朗读 $pronunciationWord" }, LinearLayout.LayoutParams(0, -2, 1f))
            next.addView(ui.button(nextLabel) {
                when {
                    answer == null -> ask("请根据本次实际作答或学习内容，直接准备一个合适的下一步练习，并简要说明依据。没有作答就不宣称已掌握，不要自动朗读。")
                    !answer.correct -> ask("请再给我出一道 ${answer.english} 的 ${answer.kind.name} 练习，巩固刚才的错误。")
                    answer.kind == org.qingjian.speech.learning.quiz.Kind.CLOZE && practiceAction != null -> { speech.stop(); org.qingjian.speech.learning.ai.tools.ActionStore(this).advancePractice(practiceAction.id); render() }
                    else -> ask("请给我出一道 ${answer.english} 的 CLOZE 搭配练习；如缺少包含原词的例句，先补充词卡。")
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(next)
        }
        messages.lastOrNull { it.role == "user" && it.state != "done" }?.let { pending ->
            column.addView(ui.button("发送／重试最近保存的问题") { ask(pending.text, pending.id) }.apply { isEnabled = !session.busy })
        }
        ui.fold(column, "对话与工具") { tools ->
            conversations.settings(tools)
            tools.addView(ui.button("只保存问题，稍后再问") {
                val question = input.text.toString().trim()
                if(question.isNotEmpty() && question.length <= 4000) {
                    store.question(session.topic, question); input.setText(""); draft = ""; render()
                } else input.error = "请输入 1～4000 字的问题"
            }.apply { isEnabled = !session.busy; id = ID_SAVE })
            tools.addView(ui.button("AI 设置") { startActivity(Intent(this, SettingsActivity::class.java)) }.apply { isEnabled = !session.busy })
            tools.addView(ui.button("添加词汇或短语") { WordEditor.show(this) { } })
            tools.addView(ui.button("清空本页对话") {
                AlertDialog.Builder(this).setMessage("清空本页保存的问题和回答？词汇与 AI 配置保留。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> store.clearMessages(session.topic); input.setText(""); expandedMessages = false; render() }.show()
            }.apply { isEnabled = !session.busy })
        }
        val scroll = ui.scroll(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = ui.column().apply { setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(6)) }
        input = ui.input("哪里不明白，直接问我…", draft, true).apply {
            id = ID_QUESTION; maxLines = 4; minLines = 1; isEnabled = !session.busy
            gravity = Gravity.TOP or Gravity.START
        }
        composer.addView(input)
        val send = ui.iconButton("发送问题", R.drawable.ic_send) {
            val question = input.text.toString()
            if(question.isNotBlank() && question.length <= 4000) { input.setText(""); draft = "" }
            ask(question)
        }.apply {
            isEnabled = !session.busy; id = ID_SEND
            background = ui.shape(ui.color(R.color.system_blue), 14)
            setCompoundDrawables(ui.icon(R.drawable.ic_send, android.graphics.Color.WHITE), null, null, null)
        }
        val composeRow = ui.row()
        composer.removeView(input)
        composeRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        composeRow.addView(send)
        composer.addView(composeRow)
        composer.addView(ui.caption("AI 回答可能有误 · 不代替发音评估").apply { textSize = 11f; gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        root.addView(composer)
        if(homeTab) root.addView(Navigation.create(this, "ai") { Navigation.open(this, it) })
        ui.insets(root); setContentView(root)
        if(messages.isNotEmpty() && !showHistory) scroll.post {
            val target = focusAction
            if(target != null) scroll.scrollTo(0, target.top) else scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    companion object {
        const val ID_QUESTION = 0x005a2001
        const val ID_SEND = 0x005a2002
        const val ID_SAVE = 0x005a2003
        const val ID_STATUS = 0x005a2004
    }
}
