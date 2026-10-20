//! 首页唯一的每日计划主行动；显示真实保存状态，不在进入首页时请求模型。
package org.qingjian.speech.learning.daily

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

object HomeCard {
    fun show(activity: Activity, parent: LinearLayout) {
        val ui = Views(activity); val plans = PlanStore(activity); val plan = plans.current()
        ui.card(parent) { card ->
            card.background = ui.shape(ui.color(R.color.hero_background), 22)
            card.addView(ui.caption(if(plan == null) "让老师根据真实记录安排" else "${plan.day} · ${if(plan.phase == "DONE") "这一轮已完成" else "进度已保存"}").apply { setTextColor(ui.color(R.color.hero_secondary)) })
            card.addView(ui.title(plan?.title ?: "今天，学得刚刚好。").apply { setTextColor(android.graphics.Color.WHITE); textSize=27f })
            card.addView(ui.text(plan?.reason ?: "结合你的学习目标、已有词汇与实际作答，安排少量新内容和复习。", 14f).apply { setTextColor(ui.color(R.color.hero_secondary)) })
            if(plan != null) {
                val results=plans.results(plan.id)
                card.addView(ui.caption("${plans.items(plan.id).count { it.learned }} 个词已看完 · 小测已提交 ${results.optInt("submitted")} / ${results.optInt("planned")} 题").apply { setTextColor(ui.color(R.color.hero_secondary)) })
            }
            card.addView(ui.button(if(plan == null) "安排今天学习" else if(plan.phase == "DONE") "查看今日小结" else "继续今天学习", true) {
                activity.startActivity(Intent(activity, StudyActivity::class.java).putExtra("plan_id", plan?.id ?: 0L).putExtra("generate", plan == null))
            }.apply { background=ui.shape(ui.color(R.color.hero_text),13);setTextColor(ui.color(R.color.hero_background)) })
        }
    }
}
