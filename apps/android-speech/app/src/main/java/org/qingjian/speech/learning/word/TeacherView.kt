//! 当前词 AI 对话留在词卡内；消息区域更新与问题草稿分离。
package org.qingjian.speech.learning.word

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.EditText
import android.text.TextWatcher
import android.text.Editable
import org.qingjian.speech.R
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.quiz.PreviewSpeech
import org.qingjian.speech.learning.ai.tools.ActionsView

class TeacherView(private val activity:Activity,private val state:State,private val speech:PreviewSpeech,private val scroll:()->Unit,private val changed:()->Unit) {
    private val ui=Views(activity)
    val fold=Fold(activity,"问问 AI 老师") {state.teacherOpen=it}
    private val messages=ui.column()
    val input:EditText=ui.input("比如：和 manage 有什么区别？",state.draft,true).apply {id=ID_QUESTION;maxLines=4;minLines=1}
    private val send=ui.iconButton("发送当前词问题",R.drawable.ic_send) {send()}.apply {id=ID_SEND}
    private val status=ui.caption("").apply {id=ID_STATUS;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    private val cancel=ui.button("取消本次 AI 请求") {state.ai.cancel()}
    private var last=""
    init {
        fold.body.addView(ui.caption("当前词：${LearningStore.get(activity).word(state.wordId)?.english.orEmpty()}"))
        fold.body.addView(messages)
        val form=ui.row();form.addView(input,LinearLayout.LayoutParams(0,-2,1f));form.addView(send);fold.body.addView(form)
        form.addOnLayoutChangeListener {view,_,_,_,_,_,_,_,_->
            if(input.hasFocus())view.post {view.requestRectangleOnScreen(android.graphics.Rect(0,0,view.width,view.height),true)}
        }
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){state.draft=s.toString()}
            override fun afterTextChanged(s:Editable?)=Unit
        })
        fold.body.addView(status);fold.body.addView(cancel);fold.body.addView(speech.label())
        fold.body.addView(ui.button("配置手机 AI") {activity.startActivity(Intent(activity,org.qingjian.speech.learning.ai.SettingsActivity::class.java))}.apply {
            visibility=if(runCatching {org.qingjian.speech.learning.ai.Credentials(activity).load().validate()}.isSuccess)View.GONE else View.VISIBLE
        })
        fold.setOpen(state.teacherOpen)
    }
    fun open(question:String?=null,sendNow:Boolean=false) {
        fold.setOpen(true)
        if(question!=null){input.setText(question);input.setSelection(input.length())}
        scroll()
        if(sendNow)send() else {input.requestFocus();input.post {activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}}
    }
    fun send() {
        if(state.ai.busy)return
        val text=input.text.toString().trim()
        if(text.isEmpty() || text.length>4000){input.error="请输入1～4000字的问题";return}
        speech.stop();state.oral.stopPlayback();state.sentDraft=text;state.ai.send(text);input.setText("")
    }
    fun refresh(allowPlayback:Boolean=false) {
        val book=LearningStore.get(activity);val entries=book.messages(state.ai.topic).takeLast(12)
        val actions=org.qingjian.speech.learning.ai.tools.ActionStore(activity).forTopic(state.ai.topic)
        val signature=entries.joinToString {"${it.id}:${it.state}:${it.text.length}"}+actions.joinToString {"${it.id}:${it.state}:${it.payload.optInt("index")}"}+state.oral.busy
        if(last!=signature || allowPlayback) {
            last=signature;speech.clearBindings(messages);messages.removeAllViews()
            val renderer=ActionsView(activity,speech,{refresh();changed()},openWord={id->
                if(id==state.wordId){changed()} else activity.startActivity(Intent(activity,org.qingjian.speech.learning.WordStudyActivity::class.java).putExtra("word_id",id))
            },ask={text->open(text,true)})
            for(entry in entries) {
                ui.card(messages) {card->
                    card.background=ui.shape(ui.color(if(entry.role=="user")R.color.chip_background else R.color.card_background),14)
                    card.addView(ui.caption(if(entry.role=="user")"我" else "青简老师"));card.addView(ui.text(entry.text,14f).apply {setTextIsSelectable(true)})
                    if(entry.role=="assistant" && entry.state=="done")card.addView(speech.button("word-ai-${entry.id}",entry.text.take(4000),"老师回复"))
                    if(entry.role=="user" && entry.state!="done")card.addView(ui.button("重试这条问题") {speech.stop();state.sentDraft=entry.text;state.ai.send(entry.text,entry.id)}.apply {isEnabled=!state.ai.busy})
                }
                val question=if(entry.role=="assistant")entry.replyTo else entry.id.takeIf {entries.none {reply->reply.replyTo==entry.id}}
                question?.let {renderer.render(messages,state.ai.topic,!state.ai.busy&&!state.oral.busy,it,
                    if(allowPlayback && state.ai.canDispatchPlayback && !state.oral.busy)state.ai.lastQuestionId else 0)}
            }
        }
        status.text=state.ai.notice.ifBlank {"回答会保存在当前词条，可离线回看。"}
        if(!state.ai.busy && input.text.isEmpty() && entries.lastOrNull {it.id==state.ai.lastQuestionId}?.state=="failed" && state.sentDraft.isNotBlank())input.setText(state.sentDraft)
        input.isEnabled=!state.ai.busy;send.isEnabled=!state.ai.busy;cancel.visibility=if(state.ai.busy)View.VISIBLE else View.GONE
    }
    companion object {const val ID_QUESTION=0x005a9401;const val ID_SEND=0x005a9402;const val ID_STATUS=0x005a9403}
}
