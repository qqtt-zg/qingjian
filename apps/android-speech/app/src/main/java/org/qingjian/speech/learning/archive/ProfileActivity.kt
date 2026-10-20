//! 我的学习概览；默认周历、可展开月历、评测次数与来源归属。
package org.qingjian.speech.learning.archive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import org.qingjian.speech.R
import org.qingjian.speech.learning.MainHeader
import org.qingjian.speech.learning.Navigation
import org.qingjian.speech.learning.Views
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

class ProfileActivity:Activity() {
    private lateinit var ui:Views
    private var selected=LocalDate.now()
    private var expanded=false
    private val archive by lazy {Store(this)}
    override fun onCreate(saved:Bundle?){super.onCreate(saved);ui=Views(this);selected=runCatching {LocalDate.parse(saved?.getString("date"))}.getOrDefault(LocalDate.now());expanded=saved?.getBoolean("expanded") ?: false}
    override fun onResume(){super.onResume();render()}
    override fun onSaveInstanceState(out:Bundle){out.putString("date",selected.toString());out.putBoolean("expanded",expanded);super.onSaveInstanceState(out)}
    private fun open(page:String){startActivity(Intent(this,ArchiveActivity::class.java).putExtra("page",page))}
    private fun render(){
        val root=ui.column();ui.insets(root);root.addView(MainHeader.create(this,"我的",settings=true));val body=ui.column()
        body.addView(ui.caption("我的学习"));body.addView(ui.text("每一步，都有记录。",24f));val events=archive.events();val answered=events.filter {it.getJSONObject("data").has("correct")};val weekStart=LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val stats=ui.row();for((n,label) in listOf(answered.size to "累计作答",answered.count {it.getJSONObject("data").optBoolean("correct")&&!it.getJSONObject("data").optBoolean("assisted")} to "独立答对",events.map {Store.day(it.getLong("created"))}.filter {!it.isBefore(weekStart)}.distinct().size to "本周学习日"))stats.addView(ui.column().apply {addView(ui.text(n.toString(),26f));addView(ui.caption(label))},LinearLayout.LayoutParams(0,-2,1f));body.addView(stats)
        val quota=getSharedPreferences("learning_archive",0).getInt("quota",500)
        val submitted=events.count {it.getString("kind")=="发音评测"&&Store.day(it.getLong("created"))==LocalDate.now()}
        ui.divider(body);body.addView(ui.menuRow("语音评分 · 免费次数","今日提交 $submitted 次 · 每日参考 $quota 次\n本机估算剩余 ${(quota-submitted).coerceAtLeast(0)} 次，实际以平台为准",R.drawable.ic_mic){open("services")});ui.divider(body)
        body.addView(ui.text("学习日历",15f));calendar(body,events)
        body.addView(ui.menuRow("学习档案与记忆","查看学习重点，纠正老师的记忆",R.drawable.ic_spark){open("memory")})
        body.addView(ui.menuRow("用量与执行","查看 AI token 和调用结果",R.drawable.ic_history){open("usage")})
        body.addView(ui.menuRow("电脑来源","生词本单向同步，电脑接触与手机成绩分开记录",R.drawable.ic_monitor){open("source")})
        body.addView(ui.menuRow("同步与备份","连接后自动备份，离线保留学习记录",R.drawable.ic_book){open("backup")})
        root.addView(ui.scroll(body),LinearLayout.LayoutParams(-1,0,1f));root.addView(Navigation.create(this,"me"){Navigation.open(this,it)});setContentView(root)
    }
    private fun calendar(parent:LinearLayout,events:List<org.json.JSONObject>){
        val start=if(expanded)selected.withDayOfMonth(1) else selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));val days=if(expanded)selected.lengthOfMonth() else 7
        ui.card(parent){card->
            val heading=ui.row();heading.addView(ui.caption(if(expanded)"${selected.year}年${selected.monthValue}月" else "${start.monthValue}月${start.dayOfMonth}日—${start.plusDays(6).dayOfMonth}日"),LinearLayout.LayoutParams(0,-2,1f))
            for(delta in listOf(-1,1))heading.addView(ui.iconButton(if(delta<0)"上一个时间段" else "下一个时间段",R.drawable.ic_chevron){selected=(if(expanded)selected.plusMonths(delta.toLong()).withDayOfMonth(1) else selected.plusWeeks(delta.toLong())).coerceAtMost(LocalDate.now());render()}.apply {if(delta<0)rotation=180f;isEnabled=delta<0||(if(expanded)selected.withDayOfMonth(1)<LocalDate.now().withDayOfMonth(1) else start.plusDays(6)<LocalDate.now())})
            card.addView(heading);val labels=ui.row();for(label in listOf("一","二","三","四","五","六","日"))labels.addView(ui.caption(label).apply {gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f));card.addView(labels)
            val offset=if(expanded)start.dayOfWeek.value-1 else 0;val cells=offset+days;val recorded=events.map {Store.day(it.getLong("created"))}.toSet()
            for(rowIndex in 0 until (cells+6)/7){val row=ui.row();for(col in 0..6){val index=rowIndex*7+col-offset;val date=start.plusDays(index.toLong());if(index !in 0 until days)row.addView(ui.text(""),LinearLayout.LayoutParams(0,ui.dp(44),1f)) else row.addView(ui.button(date.dayOfMonth.toString()+(if(date in recorded)"·" else "")){selected=date;render()}.apply {
                textSize=12f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);isEnabled=date<=LocalDate.now();alpha=if(isEnabled)1f else 0.35f;isSelected=date==selected;contentDescription="$date 学习记录";background=ui.shape(ui.color(if(date==selected)R.color.chip_background else R.color.card_background),9)
            },LinearLayout.LayoutParams(0,ui.dp(44),1f))};card.addView(row)}
            card.addView(ui.button(if(expanded)"收起月历" else "展开月历"){expanded=!expanded;render()}.apply {setBackgroundColor(android.graphics.Color.TRANSPARENT);gravity=Gravity.START;textSize=12f})
            ui.divider(card);card.addView(ui.caption("$selected · 学习记录"));val rows=events.filter {Store.day(it.getLong("created"))==selected}
            if(rows.isEmpty())card.addView(ui.caption("这一天还没有学习记录。"))
            rows.take(20).forEach {e->val d=e.getJSONObject("data");val status=if(d.has("correct"))if(d.optBoolean("correct"))if(d.optBoolean("assisted"))"提示后答对" else "独立答对" else "待巩固" else d.optString("state");card.addView(ui.caption("${e.getString("english")} · ${e.getString("kind")} · $status"))}
            if(rows.size>20)card.addView(ui.caption("当日共${rows.size}条，完整记录保留在学习档案。"))
        }
    }
}
