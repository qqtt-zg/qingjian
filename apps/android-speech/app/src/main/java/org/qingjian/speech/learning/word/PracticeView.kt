//! 原页题目与反馈；选项喇叭不提交答案，成绩复用固定题号。
package org.qingjian.speech.learning.word

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.quiz.Kind
import org.qingjian.speech.learning.quiz.OptionsView
import org.qingjian.speech.learning.quiz.PreviewSpeech

class PracticeView(private val activity: Activity,private val practice: Practice,private val speech: PreviewSpeech,private val changed:()->Unit,private val ask:(String)->Unit) {
    private val ui=Views(activity)
    val root=ui.column()
    private var key=""
    private var input: EditText?=null
    fun refresh() {
        val question=practice.current;val answer=question?.let {practice.results.answer(it.key)}
        val signature="${question?.key}|${answer?.answer}|${practice.kind}|${practice.index}|${practice.notice}"
        if(signature==key)return
        key=signature;speech.clearBindings(root);root.removeAllViews();input=null
        val selector=Spinner(activity).apply {
            id=ID_KIND;adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,Kind.entries.map {it.label});setSelection(Kind.entries.indexOf(practice.kind))
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){val selected=Kind.entries[position];if(selected!=practice.kind){speech.stop();practice.prepare(selected);changed()}}
            }
        };root.addView(selector)
        if(question==null) {
            root.addView(ui.text(if(practice.questions.isEmpty())practice.notice.ifBlank {"用当前词做一轮小练习，结果直接显示在这里。"} else practice.facts(),14f,true))
            root.addView(ui.button("重新准备当前词练习") {practice.prepare(practice.kind);changed()});return
        }
        root.addView(ui.caption("${question.kind.label} · ${practice.index+1} / ${practice.questions.size}"))
        val prompt=when(question.kind){Kind.CHOICE->question.english;Kind.CLOZE->question.prompt;else->question.meaning}
        root.addView(ui.text(prompt,19f).apply {id=ID_QUESTION})
        root.addView(ui.button("听题目") {speech.toggle("word-question",prompt)})
        if(question.kind==Kind.SPELLING && answer==null) {
            input=ui.input("填写英文词或短语",practice.draft).apply {
                id=ID_INPUT;addTextChangedListener(object:TextWatcher{
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){practice.draft=s.toString().take(1000)}
                    override fun afterTextChanged(s:Editable?)=Unit
                })
            };root.addView(input)
        } else if(question.kind!=Kind.SPELLING)OptionsView(activity,speech).render(root,question,answer,true){value->speech.stop();runCatching {practice.answer(value)}.onFailure {android.widget.Toast.makeText(activity,it.message ?: "作答未保存",android.widget.Toast.LENGTH_SHORT).show()};changed()}
        if(answer!=null) {
            root.addView(ui.text(if(answer.correct)"这次答对了。" else "这题需要再巩固。",16f))
            val feedback="本题答案：${if(question.kind==Kind.CHOICE)question.meaning else question.english}。实际作答已记录，练过一次不代表掌握。"
            root.addView(ui.text(feedback,13f,true))
            root.addView(ui.button("听解析") {speech.toggle("word-answer",feedback)})
            root.addView(ui.button("请老师解释这题") {ask("请根据我刚才关于 ${question.english} 的 ${question.kind.label} 实际作答，解释正确用法。我的答案是 ${answer.answer}；本机判定${if(answer.correct)"正确" else "错误"}。")})
        } else root.addView(ui.caption("先作答再看答案；选项旁的小喇叭只试听，不会提交。"))
        root.addView(speech.label())
    }
    fun submit():Boolean {
        val q=practice.current ?: return false
        if(q.kind!=Kind.SPELLING)return false
        val value=input?.text?.toString().orEmpty()
        if(value.isBlank()){input?.error="请先填写答案";return false}
        return runCatching {practice.answer(value);speech.stop();changed();true}.getOrElse {input?.error=it.message ?: "答案未保存";false}
    }
    companion object {const val ID_KIND=0x005a9301;const val ID_INPUT=0x005a9302;const val ID_QUESTION=0x005a9303}
}
