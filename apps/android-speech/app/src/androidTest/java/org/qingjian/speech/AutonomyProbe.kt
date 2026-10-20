//! 隔离设备验证自动执行、来源限定撤销、后续学习保护和真实题目快照。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.StudyPreferences
import org.qingjian.speech.learning.ai.ToolCall
import org.qingjian.speech.learning.ai.tools.ActionStore
import org.qingjian.speech.learning.ai.tools.Executor
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.practice.PracticeStore

object AutonomyProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        StudyPreferences(app).save(0, 0, 3, 10)
        val actions = ActionStore(app); val executor = Executor(app, automatic = true)
        val control = store.addWord("control"); store.setMeaning(control, "原笔记：控制")
        val volume = store.addWord("volume"); store.setMeaning(volume, "音量")
        val question = store.question("general", "帮我学一个词，并准备英文选词练习；不要朗读")
        fun call(name: String, args: JSONObject, q: Long = question) = executor.execute(ToolCall("test", name, args.toString()), "general", q)
        fun card(word: String) = JSONObject().put("english", word).put("meaning", "帮助")
            .put("example", "Can you $word me?").put("translation", "可以帮助我吗？")
        val saved = call("prepare_word_card", card("help"))
        check(saved.getString("status") == "saved" && store.words("help").size == 1)
        val first = saved.getLong("action_id"); val firstWord = saved.getLong("word_id")
        check(actions.get(first)?.automatic == true && actions.get(first)?.createdWord == true)
        check(call("prepare_word_card", card("help")).getLong("action_id") == first)
        check(actions.undoCard(first) && store.word(firstWord) == null)
        check(call("prepare_word_card", card("help")).getString("status") == "undone")
        val secondQ = store.question("general", "再学 help")
        val second = call("prepare_word_card", card("help"), secondQ)
        check(second.getLong("word_id") > firstWord)
        store.record("help", completed = true)
        check(!actions.undoCard(second.getLong("action_id")) && store.words("help").single().played == 1)
        val changed = call("prepare_word_card", card("ready"), secondQ)
        store.setMeaning(changed.getLong("word_id"), "我修改的笔记")
        check(!actions.undoCard(changed.getLong("action_id")))
        val existing = call("prepare_word_card", card("control"), secondQ)
        check(!actions.undoCard(existing.getLong("action_id")) && store.word(control)?.meaning == "原笔记：控制")
        check(call("request_pronunciation", JSONObject().put("word_id", control)).getString("status") == "needs_user_request")
        val noPractice = store.question("general", "只解释，不要出题，也不要朗读")
        check(call("prepare_practice", JSONObject().put("word_ids", JSONArray(listOf(control))).put("kind", "ENGLISH_CHOICE"), noPractice).getString("status") == "needs_user_request")
        val say = store.question("general", "请朗读 control")
        check(call("request_pronunciation", JSONObject().put("word_id", control), say).getString("status") == "queued_for_playback")
        val quiz = call("prepare_practice", JSONObject().put("word_ids", JSONArray(listOf(control))).put("kind", "ENGLISH_CHOICE"))
        val round = actions.practice(quiz.getLong("action_id"))
        check(quiz.getString("status") == "ready" && round.single().englishOptions && "control" in round.single().options)
        check(actions.practice(quiz.getLong("action_id")).single().key == round.single().key)
        val practice = PracticeStore(app)
        check(practice.summary(Kind.ENGLISH_CHOICE).first == 0)
        practice.submit(round.single(), "control"); practice.submit(round.single(), "control")
        check(practice.summary(Kind.ENGLISH_CHOICE) == (1 to 1))
        val cloze = call("prepare_practice", JSONObject().put("word_ids", JSONArray(listOf(second.getLong("word_id")))).put("kind", "CLOZE"))
        val clozeRound = actions.practice(cloze.getLong("action_id"))
        check(clozeRound.single().prompt == "Can you ____ me?" && "help" in clozeRound.single().options)
        val capQ = store.question("general", "教我少量新词")
        for(word in listOf("one", "two", "three")) check(call("prepare_word_card", card(word), capQ).getString("status") == "saved")
        check(call("prepare_word_card", card("four"), capQ).getString("status") == "invalid_arguments" && store.words("four").isEmpty())
        check(call("prepare_word_card", card("one"), capQ).getString("status") == "saved")
        val speechQ = store.question("general", "整理新词并朗读 echo")
        val echo = call("prepare_word_card", card("echo"), speechQ)
        val queued = call("request_pronunciation", JSONObject().put("word_id", echo.getLong("word_id")), speechQ)
        actions.claimPronunciation(queued.getLong("action_id"))
        check(!actions.undoCard(echo.getLong("action_id")))
        store.clearMessages("general")
        check(call("prepare_word_card", card("late"), capQ).getString("status") == "invalid_arguments" && store.words("late").isEmpty())
        return "PASS automatic saved words/dedup/quota, undo only unmodified owned additions, monotonic IDs, received/reviewed/old-word protection, explicit non-negated requests, real English/CLOZE questions and idempotent grades, cleared-parent rejection"
    }
}
