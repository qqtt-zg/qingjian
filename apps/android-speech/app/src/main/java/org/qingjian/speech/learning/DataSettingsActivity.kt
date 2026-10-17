//! 低频数据管理与许可说明；删除操作保留明确确认，配对和 API 配置不受影响。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import org.qingjian.speech.R

class DataSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = Views(this)
        val column = ui.column()
        val current = packageManager.getPackageInfo(packageName, 0)
        column.addView(ui.text("青简学习 ${current.versionName}", muted = true))
        ui.card(column) { card ->
            card.addView(ui.text("本机学习资料", 20f))
            card.addView(ui.text("词汇、中文笔记、AI 对话、测验与跟读录音保存在本机。", muted = true))
            card.addView(ui.button("清空本机学习记录") {
                AlertDialog.Builder(this).setMessage("删除所有词汇、中文笔记、AI 对话、测验记录和跟读录音？电脑配对、音色与 API 配置保留。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        LearningStore.get(this).clearLearning()
                        AlertDialog.Builder(this).setMessage("本机学习记录已清空").setPositiveButton("确定", null).show()
                    }.show()
            }.apply { setTextColor(getColor(R.color.system_red)) })
        }
        ui.card(column) { card ->
            card.addView(ui.button("开源许可与第三方说明") {
                val notices = assets.open("THIRD-PARTY.txt").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("开源来源与许可说明").setMessage(notices).setPositiveButton("关闭", null).show()
            })
        }
        setContentView(ui.page("数据与关于", column))
    }
}
