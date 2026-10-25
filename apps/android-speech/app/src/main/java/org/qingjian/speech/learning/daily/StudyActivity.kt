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
    private var sessionStartTime: Long = 0L

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved); ui=Views(this)
        job=(lastNonConfigurationInstance as? Job) ?: Job(applicationContext, saved?.getLong("plan_id") ?: intent.getLongExtra("plan_id",0).takeIf { it>0 } ?: plans.current()?.id ?: 0)
        revealed=saved?.getBoolean("revealed") ?: false
        job.changed={render()};render()
        if(saved == null && lastNonConfigurationInstance == null && intent.getBooleanExtra("generate",false) && job.planId==0L) job.start("CREATE")
    }

    override fun onResume() {
        super.onResume()
        sessionStartTime = android.os.SystemClock.elapsedRealtime()
        val p = plans.get(job.planId)
        if (p != null && p.formatVersion >= 2) plans.resumePlan(p.id)
        job.changed={render()};render()
    }

    override fun onPause() {
        speech.close()
        val elapsed = if (sessionStartTime > 0) android.os.SystemClock.elapsedRealtime() - sessionStartTime else 0L
        val p = plans.get(job.planId)
        if (p != null && p.formatVersion >= 2 && p.status == "RUNNING") plans.pausePlan(p.id, elapsed)
        super.onPause()
    }

    override fun onRetainNonConfigurationInstance(): Any = job
    override fun onSaveInstanceState(out: Bundle) { out.putLong("plan_id",job.planId);out.putBoolean("revealed",revealed);super.onSaveInstanceState(out) }
    override fun onDestroy() { job.changed=null;if(!isChangingConfigurations)job.close();super.onDestroy() }

    private fun render() {
        if(isFinishing || isDestroyed)return
        speech.clearBindings()
        val p=plans.get(job.planId)
        val root=ui.column();root.addView(ui.header(if(p?.status in listOf("DONE","ABANDONED") || p?.phase in listOf("SUMMARY","DONE")) "今日小结" else "今天的学习"))
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
        val activeMs = if (sessionStartTime > 0) (android.os.SystemClock.elapsedRealtime() - sessionStartTime).coerceAtLeast(0L) else 0L
        sessionStartTime = android.os.SystemClock.elapsedRealtime()
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
                "adapt" -> {
                    if (p.formatVersion >= 2) job.start("REVISE") else job.start("ADAPT")
                    return
                }
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
                        .setNegativeButton("取消",null).setPositiveButton("交给老师调整") {_,_->
                            val text = input.text.toString().take(1000)
                            if (p.formatVersion >= 2) job.start("REVISE", text) else job.start("ADAPT", text)
                        }.show();return
                }
                "v2_adjust" -> {
                    val input = ui.input("例如：今天轻松一点，后续只做一题", multiline = true)
                    AlertDialog.Builder(this).setTitle("AI 调整后续环节").setView(input).setMessage("保留已完成进度，老师只调整尚未开始的环节。")
                        .setNegativeButton("取消", null).setPositiveButton("交给老师调整") { _, _ ->
                            job.start("REVISE", input.text.toString().take(1000))
                        }.show()
                    return
                }
                "v2_complete" -> {
                    plans.completeStage(value, null, false, activeMs)
                }
                "v2_submit_spelling" -> {
                    val parts = value.split(":", limit = 3)
                    val stageKey = parts[0]
                    val answer = parts.getOrNull(1).orEmpty()
                    val assisted = parts.getOrNull(2) == "true"
                    val stage = plans.stages(p.id).first { it.stageKey == stageKey }
                    val item = plans.items(p.id).first { it.id == stage.itemId }
                    val correct = org.qingjian.speech.learning.quiz.Question.normalize(answer) == org.qingjian.speech.learning.quiz.Question.normalize(item.english)
                    val evtKey = "stage:$stageKey"
                    org.qingjian.speech.learning.archive.Store(this).event(
                        item.wordId, item.english, "拼写",
                        org.json.JSONObject().put("answer", answer).put("correct", correct).put("assisted", assisted),
                        evtKey
                    )
                    plans.completeStage(stageKey, evtKey, assisted, activeMs)
                }
                "v2_submit_choice" -> {
                    val stageKey = value.substringBefore(":")
                    val stage = plans.stages(p.id).first { it.stageKey == stageKey }
                    val item = plans.items(p.id).first { it.id == stage.itemId }
                    val evtKey = "stage:$stageKey"
                    org.qingjian.speech.learning.archive.Store(this).event(
                        item.wordId, item.english, "CHOICE",
                        org.json.JSONObject().put("answer", item.meaning).put("correct", true).put("assisted", false),
                        evtKey
                    )
                    plans.completeStage(stageKey, evtKey, false, activeMs)
                }
                "v2_rate_yes", "v2_rate_no" -> {
                    val stageKey = value
                    val stage = plans.stages(p.id).first { it.stageKey == stageKey }
                    val item = plans.items(p.id).first { it.id == stage.itemId }
                    val evtKey = "stage:$stageKey"
                    org.qingjian.speech.learning.archive.Store(this).event(
                        item.wordId, item.english, "SELF_REVIEW",
                        org.json.JSONObject().put("remembered", action == "v2_rate_yes"),
                        evtKey
                    )
                    revealed = false
                    plans.completeStage(stageKey, evtKey, false, activeMs)
                }
                "v2_abandon" -> {
                    AlertDialog.Builder(this)
                        .setTitle("放弃本轮学习")
                        .setMessage("确定放弃本轮学习？已完成的环节与积分将保留。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定放弃") { _, _ ->
                            plans.abandonPlan(p.id)
                            finish()
                        }.show()
                    return
                }
                "v2_finish" -> {
                    finish()
                    return
                }
            }
            val next=plans.get(p.id)
            if(action=="next"&&next?.phase=="ADAPT") {job.start("ADAPT");return}
            if(action in listOf("next","rate")&&next?.phase=="SUMMARY"&&next.reflection.isBlank()) {job.start("SUMMARY");return}
        } catch(e: Exception) {error=e.message ?: "本次操作未完成，已有进度保留"}
        render()
    }
}
