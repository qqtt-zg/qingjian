//! 当前词内的拼写区；不提前展示英文或含答案例句，逐字纠错不自动判定掌握。
package org.qingjian.speech.learning.spelling

import android.app.Activity
import android.graphics.Color
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.InputType
import org.qingjian.speech.R
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.quiz.PreviewSpeech

class SpellingView(activity:Activity,val session:Session,private val speech:PreviewSpeech,private val changed:()->Unit) {
    private val ui=Views(activity)
    val root=ui.column()
    fun render(){
        root.removeAllViews();val word=LearningStore.get(root.context).word(session.wordId) ?: return
        root.addView(ui.caption("先回忆拼写 · 提示会单独记录"));root.addView(ui.text(word.meaning.ifBlank {"先补充中文笔记，再开始看义拼写。"},19f))
        if(!session.submitted){
            val input=ui.input("英文拼写答案",session.draft).apply {
                id=ID_INPUT;contentDescription="英文拼写答案"
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun afterTextChanged(s:Editable?)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){session.draft=s.toString()}})
            };root.addView(input)
            root.addView(ui.button("听读音"){session.assist(1);speech.toggle("spell-target",word.english);changed()})
            root.addView(ui.button("首字母提示"){session.assist(2);changed()})
            root.addView(ui.button("查看答案"){session.assist(3);changed()})
            if(session.hint>=2)root.addView(ui.text(if(session.hint>=3)word.english else "${word.english.first()} · ${word.english.length}个字符",20f))
            root.addView(ui.button("检查拼写",true){if(input.text.isBlank())input.error="先试着写出英文" else {session.submit();changed()}}.apply {isEnabled=word.meaning.isNotBlank()})
        }else{
            root.addView(ui.title(if(session.correct)if(session.assisted)"提示后拼对" else "独立拼对" else "再留意这些字母"))
            fun difference(label:String,value:String,other:String,color:Int){
                val text=SpannableString("$label$value")
                value.forEachIndexed {i,c->if(other.getOrNull(i)!=c)text.setSpan(ForegroundColorSpan(color),label.length+i,label.length+i+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                root.addView(ui.text("",19f).apply {this.text=text})
            }
            difference("你的拼写：",session.draft,word.english,ui.color(R.color.system_red));difference("正确拼写：",word.english,session.draft,ui.color(R.color.system_green))
            root.addView(ui.caption("首次错误与提示后答对分别保留，答对一次不代表已经掌握。"))
            root.addView(ui.button("遮住再拼一次",true){session.reset();changed()})
        }
    }
    companion object {const val ID_INPUT=0x005a9501}
}
