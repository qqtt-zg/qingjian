//! 学习档案、用量与同步子页；异步进度原页显示，不创建重复设置入口。
package org.qingjian.speech.learning.archive

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import org.json.JSONObject
import org.qingjian.speech.R
import org.qingjian.speech.learning.Views

class ArchiveActivity:Activity() {
    private lateinit var ui:Views
    private lateinit var job:AnalysisJob
    private var page="memory"
    private var raw=false
    private var historyCount=100
    private val store by lazy {Store(this)}
    private val prefs by lazy {getSharedPreferences("learning_archive",0)}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,_->runOnUiThread {render()}}
    override fun onCreate(saved:Bundle?){super.onCreate(saved);ui=Views(this);page=intent.getStringExtra("page") ?: "memory";job=(lastNonConfigurationInstance as? AnalysisJob) ?: AnalysisJob(this);raw=saved?.getBoolean("raw") ?: false}
    override fun onResume(){super.onResume();job.changed={render()};prefs.registerOnSharedPreferenceChangeListener(listener);render()}
    override fun onPause(){prefs.unregisterOnSharedPreferenceChangeListener(listener);job.changed=null;super.onPause()}
    override fun onSaveInstanceState(out:Bundle){out.putBoolean("raw",raw);super.onSaveInstanceState(out)}
    @Deprecated("保留全量分析任务") override fun onRetainNonConfigurationInstance():Any=job
    override fun onDestroy(){if(!isChangingConfigurations)job.close();super.onDestroy()}
    private fun render(){
        val body=ui.column();val title=when(page){"services"->"语音评测用量";"usage"->"用量与执行";"source"->"电脑来源";"backup"->"同步与备份";else->"学习档案"}
        body.addView(ui.title(title))
        when(page){
            "memory"->{
                body.addView(ui.caption("记忆帮助选择上下文和下一步重点，不能更改实际成绩。纠正或删除后，批量分析不会覆盖你的选择。"))
                val memories=store.memories();if(memories.isEmpty())body.addView(ui.caption("尚未生成学习档案，可以分析已有的词汇和学习记录。"))
                for(memory in memories)ui.card(body){card->card.addView(ui.text(memory.getString("body"),14f));card.addView(ui.caption(memory.getString("source")+if(memory.optBoolean("corrected"))" · 已手动纠正" else ""))
                    card.addView(ui.button("纠正记忆"){val input=ui.input("你希望老师记住什么",memory.getString("body"),true);AlertDialog.Builder(this).setTitle("纠正记忆").setView(input).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->if(input.length() in 1..3000){store.edit(memory.getString("id"),input.text.toString());render()}}.show()})
                    card.addView(ui.button("删除这条记忆"){store.edit(memory.getString("id"),memory.getString("body"),true);render()})
                }
                body.addView(ui.text(job.progress(),14f));if(job.notice.isNotBlank())body.addView(ui.caption(job.notice))
                body.addView(android.widget.CheckBox(this).apply {text="本轮同时分析完整对话原文（增加用量）";isChecked=raw;isEnabled=!job.busy;setOnCheckedChangeListener {_,checked->raw=checked}})
                if(job.busy)body.addView(ui.button("暂停分析"){job.cancel()}) else {
                    body.addView(ui.button("分析／继续全部历史",true){job.start(raw=raw)})
                    body.addView(ui.button("重新生成全量快照"){job.start(fresh=true,raw=raw)})
                }
                body.addView(ui.caption("平时问答只引用记忆和近期记录；全量分析由你主动开始，分批推进，已完成批次可继续。"))
                val events=store.events().asReversed()
                ui.fold(body,"学习记录 · ${events.size}条"){list->
                    events.take(historyCount).forEach {e->val data=e.getJSONObject("data");val detail=if(data.has("answer"))"作答：${data.optString("answer").ifBlank {"未填写"}} · ${if(data.optBoolean("correct"))if(data.optBoolean("assisted"))"提示后答对" else "独立答对" else "待巩固"}" else "评测状态：${when(data.optString("state")){"succeeded"->"成功";"failed"->"失败";"cancelled"->"已取消";else->"未完成"}}";list.addView(ui.text("${Store.day(e.getLong("created"))} · ${e.getString("english")}\n${e.getString("kind")} · $detail",13f))}
                    if(events.size>historyCount)list.addView(ui.button("再显示100条"){historyCount+=100;render()})
                }
            }
            "usage"->{
                val rows=Usage(this).rows();val input=rows.sumOf {it.second.optLong("input",0)};val output=rows.sumOf {it.second.optLong("output",0)}
                body.addView(ui.text("已报告输入 $input · 输出 $output token",20f));body.addView(ui.caption("未报告的请求不参与合计。缓存是输入的子项；隐藏推理未报告时显示未知，不推算费用。"))
                if(rows.isEmpty())body.addView(ui.caption("尚无用量记录；下一次AI调用返回后会记录实际数据。"))
                rows.forEach {(task,data)->ui.fold(body,"$task · ${data.optString("state")}"){card->
                    fun value(key:String)=if(data.isNull(key))"未知／服务商未报告" else data.optString(key,"未知")
                    card.addView(ui.text("输入 ${value("input")}\n输出 ${value("output")}\n缓存 ${value("cached")}\n推理 ${value("reasoning")}\n耗时 ${data.optLong("duration_ms")} ms",13f))
                }}
            }
            "services"->{
                val rows=store.events().filter {it.getString("kind")=="发音评测"&&Store.day(it.getLong("created"))==java.time.LocalDate.now()}
                body.addView(ui.text("今日提交 ${rows.size} 次",24f));for((state,label) in listOf("succeeded" to "成功","failed" to "失败","cancelled" to "已取消","running" to "未完成"))body.addView(ui.caption("$label：${rows.count {it.getJSONObject("data").optString("state")==state}}次"))
                val quota=ui.input("每日免费额度参考",prefs.getInt("quota",500).toString()).apply {inputType=InputType.TYPE_CLASS_NUMBER};body.addView(quota)
                body.addView(ui.button("保存额度参考"){val n=quota.text.toString().toIntOrNull();if(n==null||n !in 0..100000)quota.error="填写0到100000的整数" else prefs.edit().putInt("quota",n).apply()})
                body.addView(ui.caption("账号实际剩余额度：未知。这里只统计本App提交，其他设备也可能使用免费额度，最终以讯飞平台为准。离线朗读不使用评测次数。"))
                body.addView(ui.button("评测配置"){startActivity(Intent(this,org.qingjian.speech.learning.assessment.SettingsActivity::class.java))})
            }
            else->{
                body.addView(ui.text(prefs.getString("transfer_status","连接电脑后可同步生词并自动备份学习记录").orEmpty(),14f))
                body.addView(ui.caption("电脑同步：${prefs.getString("sync_time","尚未同步")}\n手机备份：${prefs.getString("backup_time","尚未备份")}"))
                if(page=="backup"){
                    body.addView(android.widget.Switch(this).apply {text="连接时自动备份";isChecked=prefs.getBoolean("auto_backup",true);setOnCheckedChangeListener {_,checked->prefs.edit().putBoolean("auto_backup",checked).apply()}})
                    body.addView(ui.caption("自动备份保存到配对电脑的 learning-backups 目录，保留最新和上一份JSON。不含API密钥、配对凭据、模型或录音文件。电脑离线时手机继续保存，恢复连接后重试。这是电脑备份，尚未连接第三方云盘。"))
                }else{
                    body.addView(ui.caption("电脑生词本单向导入；只给手机空白中文笔记补来源，不覆盖你编辑的笔记。电脑接触次数不算手机学习成绩。"))
                    store.db.rawQuery("SELECT w.english,s.payload FROM desktop_sources s JOIN words w ON w.id=s.word_id ORDER BY w.english",null).use {c->while(c.moveToNext()){val d=JSONObject(c.getString(1));body.addView(ui.caption("${c.getString(0)} · 电脑接触 ${d.optInt("seen")} 次\n${d.optString("meaning")}"))}}
                }
                body.addView(ui.button(if(page=="source")"立即同步电脑来源" else "立即同步与备份",true){Transfer.run(this,true)})
            }
        }
        setContentView(ui.page(title,body))
    }
}
