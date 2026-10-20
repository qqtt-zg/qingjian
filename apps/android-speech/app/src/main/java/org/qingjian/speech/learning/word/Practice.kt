//! 当前词的原页练习快照；切换面板或重建保留题号、草稿和真实作答。
package org.qingjian.speech.learning.word

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.practice.PracticeStore
import org.qingjian.speech.learning.quiz.Builder
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.Question

class Practice(context: Context,val wordId: Long) {
    private val app=context.applicationContext
    private val book=LearningStore.get(app)
    val results=PracticeStore(app)
    var questions=emptyList<Question>()
        private set
    var index=0
        private set
    var kind=Kind.CLOZE
        private set
    var draft=""
    var notice=""
        private set
    val current get()=questions.getOrNull(index)
    init {
        val raw=book.readableDatabase.rawQuery("SELECT payload FROM word_workspaces WHERE word_id=?",arrayOf(wordId.toString())).use {if(it.moveToFirst())it.getString(0) else null}
        if(raw!=null)runCatching {
            val value=JSONObject(raw);kind=Kind.valueOf(value.getString("kind"));val items=value.getJSONArray("questions")
            questions=(0 until items.length()).map {Question.from(items.getJSONObject(it))};index=value.optInt("index").coerceIn(0,questions.size);draft=value.optString("draft")
            val word=checkNotNull(book.word(wordId));require(questions.all {it.wordId==wordId&&it.english==word.english&&it.meaning==word.meaning})
        }.onFailure {questions=emptyList();index=0;draft="";notice="词条资料已变化，请重新准备练习"}
    }
    fun prepare(selected: Kind?=null) {
        val word=book.word(wordId) ?: return
        if(selected==null && questions.isNotEmpty())return
        val pool=(book.words()+word).distinctBy {it.id}
        val example=org.qingjian.speech.learning.ai.tools.ActionStore(app).approvedCard(wordId)?.optString("example").orEmpty()
        val kinds=selected?.let {listOf(it)} ?: listOf(Kind.CLOZE,Kind.ENGLISH_CHOICE,Kind.SPELLING)
        if(selected!=null)kind=selected
        var built=emptyList<Question>()
        for(choice in kinds){built=Builder.build(pool,choice,setOf(wordId),examples=mapOf(wordId to example));if(built.isNotEmpty()){kind=choice;break}}
        questions=built;index=0;draft=""
        notice=if(built.isEmpty())"当前题型缺少笔记、其他选项或适合填空的例句。可换题型，或先补全学习资料。" else ""
        persist()
    }
    fun answer(text: String) {
        val question=current ?: return
        val word=book.word(wordId)
        require(word?.english==question.english && word.meaning==question.meaning){"当前词资料已变化，请重新准备题目"}
        results.submit(question,text);draft=text;persist()
    }
    fun next(){if(current?.let {results.answer(it.key)}!=null){index++;draft="";persist()}}
    fun persist() {
        if(book.word(wordId)==null)return
        val value=JSONObject().put("kind",kind.name).put("questions",JSONArray(questions.map {it.json()})).put("index",index).put("draft",draft.take(1000))
        book.writableDatabase.insertWithOnConflict("word_workspaces",null,ContentValues().apply {put("word_id",wordId);put("payload",value.toString())},SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun facts(): String {
        if(questions.isEmpty())return "当前词尚未开始本轮练习。"
        val answers=questions.mapNotNull {results.answer(it.key)}
        return "本轮当前词练习：已作答 ${answers.size} / ${questions.size}，答对 ${answers.count {it.correct}}；未作答不视为答对，练过不等于掌握。"
    }
    fun validate() {
        val word=book.word(wordId) ?: return
        if(questions.any {it.english!=word.english || it.meaning!=word.meaning}){questions=emptyList();index=0;notice="笔记已更新，之前的作答保留在历史，请重新准备练习。";persist()}
    }
}
