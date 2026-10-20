//! 词汇学习工作区：资料、跟读评分、练习、AI 与小结均在同一页完成。
package org.qingjian.speech.learning

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toolbar
import org.qingjian.speech.R
import org.qingjian.speech.learning.word.State
import org.qingjian.speech.learning.word.Fold
import org.qingjian.speech.learning.word.OralView
import org.qingjian.speech.learning.word.PracticeView
import org.qingjian.speech.learning.word.TeacherView
import org.qingjian.speech.learning.quiz.PreviewSpeech
import org.qingjian.speech.learning.quiz.Kind

class WordStudyActivity:Activity() {
    private lateinit var ui:Views
    private lateinit var state:State
    private lateinit var speech:PreviewSpeech
    private lateinit var oral:OralView
    private lateinit var practice:PracticeView
    private lateinit var teacher:TeacherView
    private lateinit var scroll:ScrollView
    private lateinit var toolbar:Toolbar
    private lateinit var meaning:TextView
    private lateinit var noteArea:LinearLayout
    private lateinit var exampleArea:LinearLayout
    private lateinit var usage:Fold
    private lateinit var history:Fold
    private lateinit var workspace:LinearLayout
    private lateinit var summary:LinearLayout
    private lateinit var oralTab:Button
    private lateinit var practiceTab:Button
    private lateinit var spellingTab:Button
    private lateinit var spelling:org.qingjian.speech.learning.spelling.SpellingView
    private lateinit var materials:LinearLayout
    private lateinit var spellingSession:org.qingjian.speech.learning.spelling.Session
    private lateinit var primary:Button
    private lateinit var summaryLink:Button
    private lateinit var other:EditText
    private lateinit var context:EditText
    private var wordId=0L
    private var example=""
    private var materialKey=""
    private var historyKey="!"
    private var resumed=false
    private var built=false
    private val book by lazy {LearningStore.get(this)}
    private val main=Handler(Looper.getMainLooper())
    private val tick=object:Runnable {override fun run(){if(resumed){oral.tick();main.postDelayed(this,250)}}}
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved);ui=Views(this);wordId=intent.getLongExtra("word_id",0)
        if(book.word(wordId)==null){finish();return}
        state=(lastNonConfigurationInstance as? State)?.takeIf {it.wordId==wordId} ?: State(this,wordId)
        if(intent.getBooleanExtra("open_oral",false))state.panel="oral"
        if(lastNonConfigurationInstance==null){state.draft=saved?.getString("draft") ?: state.draft;state.otherDraft=saved?.getString("other").orEmpty();state.contextDraft=saved?.getString("context").orEmpty();state.panel=saved?.getString("panel") ?: "oral"}
        if(saved==null&&intent.getBooleanExtra("open_spelling",false))state.panel="spelling"
        spellingSession=org.qingjian.speech.learning.spelling.Session(this,wordId)
        if(saved==null&&intent.getBooleanExtra("open_spelling",false)&&spellingSession.submitted)spellingSession.reset()
        speech=PreviewSpeech(this){state.oral.phase !in listOf("recording","stopping")}
        val reference=intent.getStringExtra("reference")
        if(reference!=null && !state.targetInitialized){runCatching {state.oral.select(reference,intent.getStringExtra("target_mode") ?: "sentence")};state.targetInitialized=true}
        build();built=true;attach();refresh()
        val oldScroll=saved?.getInt("scroll") ?: 0;scroll.post {if(intent.getBooleanExtra("open_oral",false) && saved==null)workspaceScroll() else scroll.scrollTo(0,oldScroll)}
    }
    private fun attach(){state.notes.changed={if(resumed)refresh()};state.oral.changed={if(resumed)refresh()};state.ai.changed={if(resumed){teacher.refresh(allowPlayback=true);refreshFooter()}}}
    override fun onResume(){super.onResume();if(!built)return;resumed=true;attach();refresh();main.post(tick)}
    override fun onPause(){if(built){remember();state.notes.changed=null;state.oral.changed=null;state.ai.changed=null;speech.stop();state.oral.pause(true);main.removeCallbacks(tick)};resumed=false;super.onPause()}
    override fun onStop(){if(built && !isChangingConfigurations){if(state.notes.busy)state.notes.cancel();state.oral.pause(false)};super.onStop()}
    override fun onDestroy(){if(built){speech.close();if(!isChangingConfigurations)state.close()};super.onDestroy()}
    @Deprecated("保留词卡内任务与录音状态") override fun onRetainNonConfigurationInstance():Any {remember();return state}
    override fun onSaveInstanceState(out:Bundle){if(built){remember();out.putString("draft",state.draft);out.putString("other",state.otherDraft);out.putString("context",state.contextDraft);out.putString("panel",state.panel);out.putInt("scroll",scroll.scrollY)};super.onSaveInstanceState(out)}
    private fun remember(){state.otherDraft=other.text.toString();state.contextDraft=context.text.toString();state.draft=teacher.input.text.toString();if(book.word(wordId)!=null){state.practice.persist();org.qingjian.speech.learning.ai.memory.Conversations(this).saveDraft(state.ai.topic,state.draft)}}
    private fun build() {
        val word=checkNotNull(book.word(wordId));StudyPreferences(this).lastWord=wordId
        val root=ui.column().apply {setBackgroundColor(ui.color(R.color.screen_background))};ui.insets(root)
        toolbar=ui.header("词汇学习")
        toolbar.addView(ConnectionBar(this,compact=true).view,Toolbar.LayoutParams(ui.dp(122),ui.dp(48),Gravity.END));root.addView(toolbar)
        val column=ui.column();column.addView(ui.caption("我的词库 · 围绕一个词慢慢学会"))
        materials=ui.column();column.addView(materials)
        val wordRow=ui.row();wordRow.addView(ui.word(word.english).apply {textSize=42f},LinearLayout.LayoutParams(0,-2,1f))
        wordRow.addView(speech.button("word-title",word.english,"当前词"));materials.addView(wordRow)
        val meaningRow=ui.row();meaning=ui.text("",17f)
        meaningRow.addView(meaning,LinearLayout.LayoutParams(0,-2,1f));meaningRow.addView(ui.iconButton("编辑中文笔记",R.drawable.ic_edit) {book.word(wordId)?.let {WordEditor.show(this,it){state.notes.edited();refresh()}}});materials.addView(meaningRow)
        noteArea=ui.column();materials.addView(noteArea);exampleArea=ui.column();materials.addView(exampleArea)
        usage=Fold(this,"常用搭配与用法"){state.usageOpen=it};usage.setOpen(state.usageOpen);materials.addView(usage.root)
        val workHeading=ui.row();workHeading.addView(ui.text("把这个词用起来",15f),LinearLayout.LayoutParams(0,-2,1f))
        summaryLink=ui.button("本轮小结"){showSummary()}.apply {textSize=11f;minHeight=ui.dp(36);setPadding(ui.dp(7),0,ui.dp(7),0);setBackgroundColor(android.graphics.Color.TRANSPARENT)}
        workHeading.addView(summaryLink,LinearLayout.LayoutParams(ui.dp(80),ui.dp(42)));column.addView(workHeading)
        val tabs=ui.row();oralTab=ui.button("跟读"){selectPanel("oral")};practiceTab=ui.button("练一下"){selectPanel("quiz")}
        spellingTab=ui.button("拼写"){selectPanel("spelling")}
        tabs.addView(spellingTab,LinearLayout.LayoutParams(0,ui.dp(46),1f));tabs.addView(oralTab,LinearLayout.LayoutParams(0,ui.dp(46),1f));tabs.addView(practiceTab,LinearLayout.LayoutParams(0,ui.dp(46),1f));column.addView(tabs)
        workspace=ui.column().apply {id=ID_WORKSPACE;background=ui.shape(ui.color(R.color.card_background),18,true);setPadding(ui.dp(15),ui.dp(12),ui.dp(15),ui.dp(13))};column.addView(workspace,LinearLayout.LayoutParams(-1,-2).apply {topMargin=ui.dp(10)})
        teacher=TeacherView(this,state,speech,{teacherScroll()},{refresh()});history=Fold(this,"跟读记录与自评");column.addView(history.root);column.addView(teacher.fold.root)
        val compare=Fold(this,"相近词怎么选？");other=ui.input("另一个词或短语",state.otherDraft).apply {id=ID_OTHER};context=ui.input("语境或想表达的意思（可留空）",state.contextDraft,true).apply {id=ID_CONTEXT}
        compare.body.addView(other);compare.body.addView(context);compare.body.addView(ui.button("准备辨析问题") {
            if(other.text.isBlank()||other.length()>100)other.error="请输入1～100字的词或短语"
            else if(context.length()>1000)context.error="语境请控制在1000字以内"
            else teacher.open(StudyDirection.COMPARE.question(word.english,other.text.toString(),context.text.toString()))
        }.apply {id=ID_COMPARE});teacher.fold.body.addView(compare.root)
        summary=ui.column().apply {visibility=View.GONE;id=ID_SUMMARY;background=ui.shape(ui.color(R.color.chip_background),18);setPadding(ui.dp(16),ui.dp(12),ui.dp(16),ui.dp(12))};column.addView(summary)
        oral=OralView(this,state.oral,speech,{refresh()},{text->teacher.open(text,true)}).also {it.requestRecord={startRecording()}}
        practice=PracticeView(this,state.practice,speech,{refresh()},{text->teacher.open(text,true)})
        spelling=org.qingjian.speech.learning.spelling.SpellingView(this,spellingSession,speech){refresh()}
        scroll=ui.scroll(column);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));scroll.setOnScrollChangeListener {_,_,y,_,_->toolbar.title=if(state.panel=="spelling")"练拼写" else if(y>ui.dp(140))word.english else "词汇学习"}
        val actions=ui.row().apply {setPadding(ui.dp(17),ui.dp(8),ui.dp(17),ui.dp(8));background=ui.shape(ui.color(R.color.screen_background),0)}
        actions.addView(ui.button("问老师"){if(state.panel=="spelling"){spellingSession.assist(3);selectPanel("oral")};teacher.open()}.apply {setCompoundDrawablesWithIntrinsicBounds(ui.icon(R.drawable.ic_spark),null,null,null);textSize=13f},LinearLayout.LayoutParams(ui.dp(108),ui.dp(48)))
        primary=ui.button("开始跟读",true){primaryAction()}.apply {id=ID_PRIMARY}
        actions.addView(primary,LinearLayout.LayoutParams(0,ui.dp(48),1f).apply {marginStart=ui.dp(8)});root.addView(actions);setContentView(root)
    }
    private fun refresh(){
        if(!built)return
        val word=book.word(wordId) ?: run {finish();return};meaning.text=word.meaning.ifBlank {"还没有中文笔记"}
        refreshMaterials();refreshNotes();oral.refresh(example);state.practice.validate();practice.refresh();teacher.refresh();refreshHistory();refreshWorkspace();refreshFooter()
        if(state.summaryOpen)refreshSummary()
    }
    private fun refreshMaterials() {
        val snapshot=org.qingjian.speech.learning.notes.Store(this).snapshot(wordId) ?: return
        val key="${snapshot.materials}|${snapshot.legacy}|${snapshot.meaning}"
        if(key==materialKey)return
        materialKey=key;speech.clearBindings(exampleArea);speech.clearBindings(usage.body);exampleArea.removeAllViews();usage.body.removeAllViews()
        val card=org.qingjian.speech.learning.ai.tools.ActionStore(this).approvedCard(wordId);example=card?.optString("example").orEmpty()
        if(example.isNotBlank()) {
            val row=ui.row();row.addView(ui.text(example,19f).apply {typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL)},LinearLayout.LayoutParams(0,-2,1f));row.addView(speech.button("word-example",example,"当前例句"));exampleArea.addView(ui.caption("放进一句话里"));exampleArea.addView(row);exampleArea.addView(ui.text(card!!.optString("translation"),13f,true))
        } else exampleArea.addView(ui.caption("补全学习资料后，这里显示例句与翻译。"))
        val pairs=card?.optJSONArray("collocations")
        if(pairs!=null)for(i in 0 until pairs.length()) {val item=pairs.getJSONObject(i);val row=ui.row();row.addView(ui.text(item.getString("phrase")+"\n"+item.getString("translation"),14f),LinearLayout.LayoutParams(0,-2,1f));row.addView(speech.button("word-pair-$i",item.getString("phrase"),"搭配"));usage.body.addView(row)}
        if(card?.optString("usage")?.isNotBlank()==true)usage.body.addView(ui.text(card.getString("usage"),14f,true))
        else usage.body.addView(ui.caption("暂未保存用法提示，可补全资料或在本页请老师解释。"))
        if(!state.targetInitialized){state.targetInitialized=true;if(state.oral.recording==null && example.isNotBlank())state.oral.select(example,"sentence")}
    }
    private fun refreshNotes() {
        noteArea.removeAllViews()
        if(!org.qingjian.speech.learning.notes.Store(this).complete(wordId))noteArea.addView(ui.button(if(state.notes.busy)"正在补全学习资料…" else "AI 补全学习资料"){state.notes.start()}.apply {id=ID_NOTE;isEnabled=!state.notes.busy})
        if(state.notes.notice.isNotBlank())noteArea.addView(ui.caption(state.notes.notice))
        if(state.notes.busy)noteArea.addView(ui.button("取消补全"){state.notes.cancel()})
        if(state.notes.canUndo)noteArea.addView(ui.button("撤销本次补全"){state.notes.undo()})
        if(state.notes.needsConfiguration)noteArea.addView(ui.button("配置手机 AI"){startActivity(Intent(this,org.qingjian.speech.learning.ai.SettingsActivity::class.java))})
    }
    private fun refreshWorkspace() {
        val target=when(state.panel){"spelling"->spelling.root;"oral"->oral.root;else->practice.root}
        if(state.panel=="spelling"){spelling.render();toolbar.title="练拼写"}
        materials.visibility=if(state.panel=="spelling")View.GONE else View.VISIBLE
        history.root.visibility=if(state.panel=="spelling")View.GONE else View.VISIBLE
        teacher.fold.root.visibility=if(state.panel=="spelling")View.GONE else View.VISIBLE
        if(workspace.childCount!=1 || workspace.getChildAt(0)!==target){workspace.removeAllViews();(target.parent as? LinearLayout)?.removeView(target);workspace.addView(target)}
        oralTab.isEnabled=!state.oral.busy || state.panel=="oral";practiceTab.isEnabled=!state.oral.busy || state.panel=="quiz"
        spellingTab.isEnabled=!state.oral.busy
        for((button,panel) in listOf(spellingTab to "spelling",oralTab to "oral",practiceTab to "quiz")){button.background=ui.shape(ui.color(if(state.panel==panel)R.color.chip_background else R.color.screen_background),10);button.isSelected=state.panel==panel}
    }
    private fun selectPanel(value:String){if(state.oral.busy && value!=state.panel)return;if(state.panel=="spelling"&&value!="spelling")spellingSession.assist(3);speech.stop();state.oral.stopPlayback();state.summaryOpen=false;summary.visibility=View.GONE;state.panel=value;if(value=="quiz")state.practice.prepare();refresh();workspaceScroll()}
    private fun primaryAction(){
        if(state.panel=="spelling"){if(spellingSession.submitted)spellingSession.reset() else if(spellingSession.draft.isNotBlank())spellingSession.submit();refresh();return}
        if(state.summaryOpen){state.summaryOpen=false;summary.visibility=View.GONE;refreshFooter();workspaceScroll();return}
        if(state.panel=="oral")when(state.oral.phase){"recording"->state.oral.stopRecording();"stopping","scoring"->Unit;"idle"->startRecording();"scored"->selectPanel("quiz");else->{speech.stop();state.oral.assess()}}
        else {val q=state.practice.current;when {q==null->{if(state.practice.questions.isEmpty())state.practice.prepare() else showSummary()};state.practice.results.answer(q.key)!=null->{if(state.practice.index+1<state.practice.questions.size)state.practice.next() else showSummary()};q.kind==Kind.SPELLING->practice.submit()};refresh()}
    }
    private fun refreshFooter(){
        summaryLink.isEnabled=!state.oral.busy
        primary.isEnabled=true
        primary.text=when {
            state.panel=="spelling"->if(spellingSession.submitted)"遮住再拼一次" else "检查拼写"
            state.summaryOpen->"继续学习"
            state.panel=="oral"->when(state.oral.phase){"recording"->"停止录音";"stopping"->{primary.isEnabled=false;"正在保存录音…"};"scoring"->{primary.isEnabled=false;"正在评估发音…"};"recorded"->"评估发音";"scored"->"练一下";else->"开始跟读"}
            state.practice.current==null->if(state.practice.questions.isEmpty())"准备练习" else "本轮小结"
            state.practice.current!!.let {state.practice.results.answer(it.key)}!=null->if(state.practice.index+1<state.practice.questions.size)"下一题" else "本轮小结"
            state.practice.current!!.kind==Kind.SPELLING->"提交答案"
            else->{primary.isEnabled=false;"先选一个答案"}
        }
    }
    private fun startRecording(){
        if(state.oral.busy)return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),31);return}
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(primary.windowToken,0);currentFocus?.clearFocus();speech.stop();state.summaryOpen=false;summary.visibility=View.GONE;state.panel="oral";state.oral.startRecording();refresh();workspaceScroll()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==31 && resumed && results.firstOrNull()==PackageManager.PERMISSION_GRANTED)startRecording()}
    private fun refreshHistory() {
        val records=org.qingjian.speech.learning.practice.PracticeStore(this).recordings(wordId)
        val key=records.joinToString {"${it.id}:${it.rating}:${org.qingjian.speech.learning.assessment.Store(this).latest(it.id)?.let {a->a.id+a.state}}"}
        if(key==historyKey)return
        historyKey=key;history.body.removeAllViews()
        if(records.isEmpty())history.body.addView(ui.caption("还没有保存跟读录音。"))
        for(record in records.take(5))ui.card(history.body){card->
            card.addView(ui.text(record.reference,15f));val result=org.qingjian.speech.learning.assessment.Store(this).latest(record.id)
            card.addView(ui.caption("%.1f 秒 · %s".format(record.duration/1000.0,result?.result?.let {"讯飞评分 %.1f".format(it.total)} ?: "尚未评估")))
            card.addView(ui.button("查看这段录音与评分"){state.oral.selectRecording(record);state.panel="oral";refresh();workspaceScroll()}.apply {isEnabled=!state.oral.busy})
            val scores=org.qingjian.speech.learning.assessment.Store(this).history(record.id)
            if(scores.isNotEmpty()) {
                val scoreHistory=Fold(this,"历史评分 · ${scores.size} 次")
                for(score in scores)scoreHistory.body.addView(ui.button("%.1f 分 · %s".format(score.result!!.total,java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.CHINA).format(java.util.Date(score.created)))) {
                    state.oral.selectAssessment(record,score.id);state.panel="oral";refresh();workspaceScroll()
                }.apply {isEnabled=!state.oral.busy})
                card.addView(scoreHistory.root)
            }
            val practiceStore=org.qingjian.speech.learning.practice.PracticeStore(this)
            card.addView(ui.caption("自评："+when(record.rating){"again"->"还需练习";"satisfied"->"比较满意";else->"尚未自评"}))
            val row=ui.row();for((label,value) in listOf("还需练习" to "again","比较满意" to "satisfied"))row.addView(ui.button(label){practiceStore.rateRecording(record.id,value);refreshHistory()},LinearLayout.LayoutParams(0,-2,1f));card.addView(row)
            card.addView(ui.button("删除这段录音"){AlertDialog.Builder(this).setMessage("删除这段录音及对应评分？其他学习记录保留。").setNegativeButton("取消",null).setPositiveButton("删除"){_,_->practiceStore.deleteRecording(record);state.oral.forgetRecording(record.id);refreshHistory()}.show()}.apply {isEnabled=!state.oral.busy})
        }
    }
    private fun summaryText():String {
        val score=state.oral.assessment?.takeIf {it.state=="succeeded"}?.result?.let {"本次讯飞发音总分%.1f分。".format(it.total)} ?: "本次跟读尚未完成有效评测。"
        return score+state.practice.facts()+"可根据相对薄弱项再读一次，或请老师给出建议。"
    }
    private fun refreshSummary(){summary.removeAllViews();summary.addView(ui.title("本轮小结"));summary.addView(ui.text(summaryText(),14f));summary.addView(ui.caption("朗读、自评、练习答题与发音评分分别记录，不能直接等同于已掌握。"));summary.addView(ui.button("听小结"){speech.toggle("word-summary",summaryText())});summary.addView(ui.button("请老师给下一步建议"){teacher.open("请根据本机实际学习记录给 ${book.word(wordId)?.english} 的下一步建议。${summaryText()}",true)})}
    private fun showSummary(){state.summaryOpen=true;refreshSummary();summary.visibility=View.VISIBLE;refreshFooter();scroll.post {scroll.smoothScrollTo(0,summary.top)}}
    private fun workspaceScroll(){scroll.post {scroll.smoothScrollTo(0,workspace.top-ui.dp(12))}}
    private fun teacherScroll(){scroll.post {scroll.smoothScrollTo(0,teacher.fold.root.top-ui.dp(8))}}
    companion object {const val ID_OTHER=0x005a3001;const val ID_CONTEXT=0x005a3002;const val ID_COMPARE=0x005a3003;const val ID_NOTE=0x005a3004;const val ID_PRIMARY=0x005a3005;const val ID_WORKSPACE=0x005a3006;const val ID_SUMMARY=0x005a3007}
}
