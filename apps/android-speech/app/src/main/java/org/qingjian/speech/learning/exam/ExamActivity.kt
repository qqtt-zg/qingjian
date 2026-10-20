//! 考试准备、逐题草稿与延迟反馈成绩；暂停和进程重建都不会丢答案。
package org.qingjian.speech.learning.exam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.LinearLayout
import org.json.JSONObject
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.quiz.PreviewSpeech

class ExamActivity:Activity() {
    private lateinit var ui:Views
    private lateinit var speech:PreviewSpeech
    private val store by lazy {Store(this)}
    private var data:JSONObject?=null
    private var scope="全部词汇"
    private var count=10
    private var selected=mutableSetOf<Long>()
    private var choosing=false
    override fun onCreate(saved:Bundle?){super.onCreate(saved);ui=Views(this);speech=PreviewSpeech(this);scope=saved?.getString("scope") ?: scope;count=saved?.getInt("count") ?: count;selected=saved?.getLongArray("selected")?.toMutableSet() ?: mutableSetOf();data=saved?.getString("exam")?.let {store.get(it)};if(data==null&&intent.getBooleanExtra("results",false))data=store.latest()?.takeIf {it.optBoolean("graded")};render()}
    override fun onSaveInstanceState(out:Bundle){out.putString("scope",scope);out.putInt("count",count);out.putLongArray("selected",selected.toLongArray());data?.let {out.putString("exam",it.getString("id"))};super.onSaveInstanceState(out)}
    override fun onPause(){speech.stop();super.onPause()}
    override fun onResume(){super.onResume();data?.let {data=store.get(it.getString("id"))};render()}
    override fun onDestroy(){speech.close();super.onDestroy()}
    private fun render(){
        val root=ui.column();ui.insets(root);root.addView(ui.header(if(data?.optBoolean("graded")==true)"本次成绩" else "独立小测"));val body=ui.column();val actions=ui.column().apply {setPadding(ui.dp(18),ui.dp(5),ui.dp(18),ui.dp(8))}
        val exam=data
        if(exam==null){
            body.addView(ui.title("检验自己，记住多少。"));body.addView(ui.caption("先完成整轮再看答案；空白记为未答对，朗读次数单独保存。"))
            val old=store.latest()?.takeUnless {it.optBoolean("graded")}
            if(old!=null)actions.addView(ui.button("继续未完成小测",true){data=old;render()})
            else {
                for(label in listOf("今日接触","本周接触","全部词汇","待巩固","自选词汇"))body.addView(ui.button((if(scope==label)"✓ " else "")+label){scope=label;selected.clear();render()})
                if(scope=="自选词汇")store.pool(scope).forEach {word->body.addView(android.widget.CheckBox(this).apply {text=word.english;isChecked=word.id in selected;setOnCheckedChangeListener {_,checked->if(checked)selected+=word.id else selected-=word.id}})}
                val amount=android.widget.Spinner(this).apply {adapter=android.widget.ArrayAdapter(this@ExamActivity,android.R.layout.simple_spinner_dropdown_item,listOf(5,10,20,30));setSelection(listOf(5,10,20,30).indexOf(count));onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:android.widget.AdapterView<*>?)=Unit;override fun onItemSelected(p:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){this@ExamActivity.count=listOf(5,10,20,30)[pos]}}};body.addView(amount)
                actions.addView(ui.button("开始小测",true){runCatching {store.create(scope,count,if(scope=="自选词汇")selected else null)}.onSuccess {data=it;render()}.onFailure {android.widget.Toast.makeText(this,it.message,android.widget.Toast.LENGTH_LONG).show()}})
            }
        }else if(exam.optBoolean("graded")){
            val questions=exam.getJSONArray("questions");body.addView(ui.title("答对 ${exam.getInt("correct")} / ${questions.length()} 题"));body.addView(ui.caption("成绩仅来自已交卷答案，不是掌握程度。"))
            for(i in 0 until questions.length()){val q=questions.getJSONObject(i);ui.card(body){card->card.addView(ui.word(q.getString("english")).apply {textSize=25f});card.addView(ui.caption(q.getString("meaning")));card.addView(ui.text("你的答案：${q.getString("answer").ifBlank {"未作答"}} · ${if(q.getBoolean("correct"))"正确" else "待巩固"}",14f));if(!q.getBoolean("correct"))card.addView(ui.button("练这个错词"){startActivity(Intent(this,WordStudyActivity::class.java).putExtra("word_id",q.getLong("word_id")).putExtra("open_spelling",true))})}}
            actions.addView(ui.button("请 AI 解读本次结果"){startActivity(Intent(this,TutorActivity::class.java).putExtra("question","请根据我的独立考试实际记录，解释错词并建议下一步学习。"))})
            actions.addView(ui.button("返回学习",true){finish()})
        }else{
            val questions=exam.getJSONArray("questions");val index=exam.getInt("index");val q=questions.getJSONObject(index)
            body.addView(ui.caption("${index+1} / ${questions.length()} · ${q.getString("mode")}"))
            body.addView(ui.title(when(q.getString("mode")){"听写"->"听读音，写出英文";"语境填词"->q.getString("prompt");else->q.getString("meaning")}))
            if(q.getString("mode")=="听写")body.addView(ui.button("播放题目"){q.put("replays",q.getInt("replays")+1);store.save(exam);speech.toggle("exam-$index",q.getString("english"))})
            body.addView(ui.input("小测英文答案",q.getString("answer")).apply {
                contentDescription="小测英文答案";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun afterTextChanged(s:Editable?)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){q.put("answer",s.toString().take(500));store.save(exam)}})
            })
            body.addView(ui.button("答题卡"){val labels=(0 until questions.length()).map {"第${it+1}题 · ${if(questions.getJSONObject(it).getString("answer").isBlank())"未作答" else "已填写"}"};AlertDialog.Builder(this).setTitle("选择题目").setItems(labels.toTypedArray()){_,which->exam.put("index",which);store.save(exam);speech.stop();render()}.show()})
            if(index>0)body.addView(ui.button("上一题"){exam.put("index",index-1);store.save(exam);speech.stop();render()})
            body.addView(ui.button("暂存并返回学习"){finish()})
            actions.addView(ui.button(if(index+1<questions.length())"下一题" else "交卷",true){speech.stop();if(index+1<questions.length()){exam.put("index",index+1);store.save(exam);render()}else AlertDialog.Builder(this).setMessage("交卷后统一查看成绩；未填写的题记为未答对。").setNegativeButton("继续检查",null).setPositiveButton("交卷"){_,_->data=store.submit(exam.getString("id"));render()}.show()})
        }
        root.addView(ui.scroll(body),LinearLayout.LayoutParams(-1,0,1f));root.addView(actions);setContentView(root)
    }
}
