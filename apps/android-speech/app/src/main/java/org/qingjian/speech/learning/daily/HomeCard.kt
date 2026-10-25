//! 首页唯一的每日计划主行动；显示真实保存状态，纯本机排程一键开始，不请求模型。
package org.qingjian.speech.learning.daily

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

object HomeCard {
    fun show(activity: Activity, parent: LinearLayout) {
        val ui = Views(activity); val plans = PlanStore(activity); val plan = plans.current()
        ui.card(parent) { card ->
            card.background = ui.shape(ui.color(R.color.hero_background), 22)
            val isDone = plan != null && (plan.status == "DONE" || (plan.formatVersion == 1 && plan.phase == "DONE"))
            val isPaused = plan != null && (plan.status in listOf("PAUSED", "RUNNING") || (plan.formatVersion == 1 && plan.phase != "DONE"))

            val statusText = when {
                plan == null -> "今日学习未开始"
                isDone -> "${plan.day} · 本轮已完成"
                isPaused -> "${plan.day} · 进度已保存"
                else -> "${plan.day} · 计划已就绪"
            }
            card.addView(ui.caption(statusText).apply { setTextColor(ui.color(R.color.hero_secondary)) })
            card.addView(ui.title(plan?.title ?: "今天，学得刚刚好。").apply { setTextColor(android.graphics.Color.WHITE); textSize=27f })
            card.addView(ui.text(plan?.reason ?: "结合你的学习目标、记忆到期与作答记录，精选适量任务。", 14f).apply { setTextColor(ui.color(R.color.hero_secondary)) })

            if(plan != null) {
                val progressText = if (plan.formatVersion >= 2) {
                    val stages = plans.stages(plan.id)
                    val doneCount = stages.count { it.status == "done" }
                    val totalCount = stages.size
                    "进度：${plan.doneCredit} / ${plan.plannedCredit} 积分 · 已完成 $doneCount / $totalCount 环节"
                } else {
                    val results = plans.results(plan.id)
                    "${plans.items(plan.id).count { it.learned }} 个词已看完 · 小测已提交 ${results.optInt("submitted")} / ${results.optInt("planned")} 题"
                }
                card.addView(ui.caption(progressText).apply { setTextColor(ui.color(R.color.hero_secondary)) })
            }

            val buttonLabel = when {
                plan == null -> "开始今日学习"
                isDone -> "再来一轮加练"
                else -> "继续今天学习"
            }

            card.addView(ui.button(buttonLabel, true) {
                if (plan == null || isDone) {
                    val newPlan = PlanBuilder.buildAndSave(activity)
                    if (newPlan != null) {
                        activity.startActivity(Intent(activity, StudyActivity::class.java).putExtra("plan_id", newPlan.id))
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle("今日已全部掌握")
                            .setMessage("词库中的词汇当前均处于稳固状态，没有到期复习任务。可以好好休息，或前往词库添加新词！")
                            .setPositiveButton("好的", null)
                            .show()
                    }
                } else {
                    activity.startActivity(Intent(activity, StudyActivity::class.java).putExtra("plan_id", plan.id))
                }
            }.apply { background=ui.shape(ui.color(R.color.hero_text),13);setTextColor(ui.color(R.color.hero_background)) })
        }
    }
}

