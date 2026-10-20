//! 原词卡内的跟读、回放和真实评测反馈；主操作交给固定底栏。
package org.qingjian.speech.learning.word

import android.app.Activity
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.oral.Session
import org.qingjian.speech.learning.quiz.PreviewSpeech
import org.qingjian.speech.learning.assessment.WordResult

class OralView(private val activity: Activity,private val session: Session,private val speech: PreviewSpeech,
    private val changed:()->Unit,private val ask:(String)->Unit) {
    private val ui=Views(activity)
    val root=ui.column()
    private var key=""
    private var selected=""
    private var status: TextView?=null
    private var clock: TextView?=null
    private var example=""
    fun refresh(currentExample: String) {
        example=currentExample
        val signature="${session.phase}|${session.reference}|${session.assessment?.id}|${session.assessment?.state}|${session.playing}|$selected|$example"
        if(signature==key){status?.text=session.notice;tick();return}
        key=signature;speech.clearBindings(root);root.removeAllViews();clock=null
        val row=ui.row();row.addView(ui.caption("跟读${if(session.mode=="word")"这个词" else "这一句"}"),LinearLayout.LayoutParams(0,-2,1f))
        for((label,kind,text) in listOf(Triple("单词","word",org.qingjian.speech.learning.LearningStore.get(activity).word(session.wordId)?.english.orEmpty()),Triple("例句","sentence",example))) {
            row.addView(ui.button(label) {speech.stop();session.select(text,kind);changed()}.apply {
                id=if(kind=="word")ID_WORD else ID_SENTENCE;textSize=12f;minHeight=ui.dp(36);minimumHeight=ui.dp(36);setPadding(ui.dp(9),0,ui.dp(9),0)
                background=ui.shape(ui.color(if(session.mode==kind)R.color.chip_background else R.color.card_background),8)
                isEnabled=!session.busy && text.isNotBlank()
            },LinearLayout.LayoutParams(ui.dp(57),ui.dp(40)))
        }
        root.addView(row)
        val target=ui.row();target.addView(ui.text(session.reference,19f).apply {typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL)},LinearLayout.LayoutParams(0,-2,1f))
        target.addView(speech.button("oral-reference",session.reference).apply {isEnabled=session.phase !in listOf("recording","stopping")})
        root.addView(target)
        status=ui.text(session.notice,12f,true).apply {id=ID_STATUS;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};root.addView(status)
        when(session.phase) {
            "recording","stopping"->{clock=ui.text("",16f).apply {id=ID_CLOCK;setTextColor(ui.color(R.color.system_blue))};root.addView(clock);tick();root.addView(ui.button("取消本次录音") {session.discardRecording()})}
            "scoring"->{root.addView(ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {isIndeterminate=true;contentDescription="正在评估发音"});root.addView(ui.button("取消评测，保留录音") {session.cancelAssessment()}.apply {id=ID_CANCEL})}
            else->{
                session.recording?.let {clip->
                    root.addView(ui.caption("已保存 %.1f 秒录音 · %s".format(clip.duration/1000.0,if(clip.mode=="word")"单词跟读" else "句子跟读")))
                    val actions=ui.row()
                    actions.addView(ui.button(if(session.playing)"停止回放" else "听我的录音") {speech.stop();session.play()},LinearLayout.LayoutParams(0,-2,1f))
                    actions.addView(ui.button("重录") {changedRecord()},LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=ui.dp(7)})
                    root.addView(actions)
                }
                val result=session.assessment?.takeIf {it.state=="succeeded"}?.result
                if(result!=null) {
                    root.addView(ui.caption("讯飞评测 · 实际返回结果"));root.addView(ui.text("%.1f / 100".format(result.total),35f).apply {typeface=android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL)})
                    fun metric(name:String,value:Double?) {if(value!=null)root.addView(ui.text("$name  ${"%.1f".format(value)}",13f,true))}
                    metric("准确度",result.accuracy)
                    if(session.mode=="sentence"){metric("流利度",result.fluency);metric("完整度",result.completeness)}
                    val weak=result.words.filter {it.issue.isNotBlank() || it.score?.let {v->v<80}==true}.sortedBy {it.score ?: 101.0}.take(4)
                    if(weak.isNotEmpty())feedback(weak) else root.addView(ui.caption("服务未返回明确的逐词问题；可听听自己的录音，再决定是否继续练习。"))
                    root.addView(ui.button("听评测反馈") {speech.toggle("assessment-feedback",feedbackText())})
                    root.addView(ui.button("重新评测这段录音") {speech.stop();session.assess()})
                    root.addView(ui.button("请老师解释评分") {ask("请根据本机保存的讯飞评测结果，解释 ${session.reference} 的练习重点。只引用真实返回的分数，不推测未返回的音素错误。")})
                } else if(session.recording==null)root.addView(ui.caption("点击底部开始跟读。停止后自动保存在手机，点击评估才会上传给讯飞。"))
                if(runCatching {org.qingjian.speech.learning.assessment.Credentials(activity).load().validate()}.isFailure)root.addView(ui.button("配置口语评测") {
                    activity.startActivity(Intent(activity,org.qingjian.speech.learning.assessment.SettingsActivity::class.java))
                })
            }
        }
        root.addView(speech.label())
    }
    var requestRecord:(()->Unit)?=null
    private fun changedRecord(){requestRecord?.invoke()}
    fun tick(){clock?.text=if(session.phase=="stopping")"正在保存录音…" else "● 录音中 00:${session.elapsed.toString().padStart(2,'0')} / 00:30"}
    private fun feedback(words: List<WordResult>) {
        root.addView(ui.caption("点标出的词查看反馈"))
        val text=SpannableString(session.reference)
        for(word in words)Regex(Regex.escape(word.text),RegexOption.IGNORE_CASE).findAll(session.reference).forEach {match->
            text.setSpan(object:ClickableSpan(){override fun onClick(widget:View){selected=word.text;key="";refresh(example)}},match.range.first,match.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        root.addView(ui.text("",17f).apply {this.text=text;movementMethod=LinkMovementMethod.getInstance()})
        val value=words.firstOrNull {it.text==selected} ?: words.first()
        val detail=ui.column().apply {background=ui.shape(ui.color(R.color.chip_background),12);setPadding(ui.dp(11),ui.dp(9),ui.dp(11),ui.dp(9))}
        detail.addView(ui.text(value.text+value.score?.let {" · %.1f".format(it)}.orEmpty(),17f))
        detail.addView(ui.text(value.issue.ifBlank {"这是本次相对薄弱的词，可先听示范再读一次。"},12f,true))
        if(value.phones.isNotEmpty())detail.addView(ui.caption("服务返回的相关音素：${value.phones.joinToString("、")}"))
        detail.addView(speech.button("problem-${value.text}",value.text));root.addView(detail)
    }
    fun feedbackText():String {
        val result=session.assessment?.result ?: return "这段录音尚未完成评测。"
        val issues=result.words.filter {it.issue.isNotBlank()}.take(3).joinToString("；"){"${it.text}：${it.issue}"}
        return "本次讯飞发音评测总分${"%.1f".format(result.total)}分。"+if(issues.isNotEmpty())issues else "可以听示范并回放自己的录音，再选择下一步练习。"
    }
    companion object {const val ID_WORD=0x005a9201;const val ID_SENTENCE=0x005a9202;const val ID_STATUS=0x005a9203;const val ID_CLOCK=0x005a9204;const val ID_CANCEL=0x005a9205}
}
