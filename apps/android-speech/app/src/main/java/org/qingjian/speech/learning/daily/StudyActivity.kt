//! 单轮日学习：固定主行动、真实 Agent 阶段反馈与本机可恢复进度。
package org.qingjian.speech.learning.daily

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.qingjian.speech.learning.TutorActivity
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.ai.SettingsActivity
import org.qingjian.speech.learning.quiz.PreviewSpeech

class StudyActivity : Activity() {
    private lateinit var ui: Views
    private lateinit var job: Job
    private val plans by lazy { PlanStore(this) }
    private val speech by lazy { PreviewSpeech(this) }
    private var revealed = false
    private var error = ""

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved); ui=Views(this)
        job=(lastNonConfigurationInstance as? Job) ?: Job(applicationContext, saved?.getLong("plan_id") ?: intent.getLongExtra("plan_id",0).takeIf { it>0 } ?: plans.current()?.id ?: 0)
        revealed=saved?.getBoolean("revealed") ?: false
        job.changed={render()};render()
        if(saved == null && lastNonConfigurationInstance == null && intent.getBooleanExtra("generate",false) && job.planId==0L) job.start("CREATE")
    }

    override fun onResume() { super.onResume();job.changed={render()};render() }
    override fun onPause() { speech.close();super.onPause() }
    override fun onRetainNonConfigurationInstance(): Any = job
    override fun onSaveInstanceState(out: Bundle) { out.putLong("plan_id",job.planId);out.putBoolean("revealed",revealed);super.onSaveInstanceState(out) }
    override fun onDestroy() { job.changed=null;if(!isChangingConfigurations)job.close();super.onDestroy() }

    private fun render() {
        if(isFinishing || isDestroyed)return
        speech.clearBindings()
        val p=plans.get(job.planId)
        val root=ui.column();root.addView(ui.header(if(p?.phase in listOf("SUMMARY","DONE")) "今日小结" else "今天的学习"))
        val body=ui.column();val foot=ui.column().apply { setPadding(ui.dp(18),ui.dp(8),ui.dp(18),ui.dp(8)) }
        if(job.busy) {
            body.addView(ProgressBar(this));body.addView(ui.text(job.notice,16f).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE })
            body.addView(ui.caption("使用你配置的 AI 服务，可能产生接口用量；已有学习进度保留。"))
            foot.addView(ui.button("取消本次请求") {job.cancel()})
        } else {
            if(job.notice.isNotBlank() && job.notice!="已根据真实资料更新，可继续学习") body.addView(ui.caption(job.notice))
            if(error.isNotBlank()) body.addView(ui.text(error,13f,true))
            if(p == null) {
                body.addView(ui.title("让今天的学习\n适合你。"));body.addView(ui.text("老师先读取真实词库与作答，再选择内容。没有足够资料时，会从简单常用表达开始。",14f,true))
                body.addView(ui.button("AI 设置") {startActivity(Intent(this,SettingsActivity::class.java))})
                foot.addView(ui.button("安排今天学习",true) {job.start("CREATE")})
            } else {
                StudyViews(this,plans,speech) { code,value ->
                    if(plans.get(p.id)?.revision==p.revision)act(code,value)
                    else {error="进度已变化，已恢复到当前步骤";render()}
                }.show(p,body,foot,revealed)
            }
        }
        root.addView(ui.scroll(body),LinearLayout.LayoutParams(-1,0,1f));root.addView(foot);ui.insets(root);setContentView(root)
    }

    private fun act(action: String, value: String = "") {
        if(job.busy)return
        val p=plans.get(job.planId) ?: return
        try {
            error="";speech.stop()
            when(action) {
                "learn" -> plans.markLearned(p.id,value.toLong())
                "choose" -> {plans.draft(p.id,value);if(p.phase=="PRACTICE")plans.submit(p.id)}
                "submit" -> {plans.submit(p.id);if(p.phase=="EXAM")plans.advance(p.id)}
                "next" -> plans.advance(p.id)
                "hint" -> plans.assist(p.id)
                "reveal" -> revealed=true
                "rate" -> {plans.rate(p.id,value=="yes");revealed=false}
                "adapt" -> {job.start("ADAPT");return}
                "skip" -> plans.skipAdjustment(p.id)
                "summary" -> {job.start("SUMMARY","请依据本轮真实作答、辅助和自评结果给出简短回顾与下一步建议。");return}
                "finish" -> {if(p.phase=="SUMMARY")plans.advance(p.id);finish();return}
                "ask" -> {
                    val wordId=value.toLong();val item=plans.items(p.id).first {it.wordId==wordId}
                    startActivity(Intent(this,TutorActivity::class.java).putExtra("word_id",wordId).putExtra("question","我在今天的学习中遇到 ${item.english}，请用简单例句解释它的日常用法。"));return
                }
                "adjust" -> {
                    val input=ui.input("例如：今天轻松一点，后续只做一题",multiline=true)
                    AlertDialog.Builder(this).setTitle("调整后续小测").setView(input).setMessage("保留已完成进度，老师只调整尚未开始的小测。")
                        .setNegativeButton("取消",null).setPositiveButton("交给老师调整") {_,_->job.start("ADAPT",input.text.toString().take(1000))}.show();return
                }
            }
            val next=plans.get(p.id)
            if(action=="next"&&next?.phase=="ADAPT") {job.start("ADAPT");return}
            if(action in listOf("next","rate")&&next?.phase=="SUMMARY"&&next.reflection.isBlank()) {job.start("SUMMARY");return}
        } catch(e: Exception) {error=e.message ?: "本次操作未完成，已有进度保留"}
        render()
    }
}
