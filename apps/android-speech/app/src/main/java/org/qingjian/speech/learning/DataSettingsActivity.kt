//! 低频数据管理与许可说明；删除操作保留明确确认，配对和 API 配置不受影响。
package org.qingjian.speech.learning

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import org.qingjian.speech.R
import org.qingjian.speech.learning.glossary.GlossaryDownload
import org.qingjian.speech.learning.glossary.GlossaryRelease
import java.util.concurrent.Executors

class DataSettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = Views(this)
        val column = ui.column()
        val current = packageManager.getPackageInfo(packageName, 0)
        column.addView(ui.text("青简学习 ${current.versionName}", muted = true))
        ui.card(column) { card ->
            card.addView(ui.text("本机学习资料", 20f))
            card.addView(ui.text("词汇、中文笔记、AI 对话与操作草稿、测验和跟读录音保存在本机。", muted = true))
            card.addView(ui.button("清空本机学习记录") {
                AlertDialog.Builder(this).setMessage("删除所有词汇、中文笔记、AI 对话、操作草稿、测验记录和跟读录音？电脑配对、音色与 API 配置保留。")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        LearningStore.get(this).clearLearning()
                        AlertDialog.Builder(this).setMessage("本机学习记录已清空").setPositiveButton("确定", null).show()
                    }.show()
            }.apply { setTextColor(getColor(R.color.system_red)) })
        }
        ui.card(column) { card ->
            card.addView(ui.text("词汇表", 20f))
            card.addView(ui.text("随包词汇表提供中英互查，建词与查词都在本机完成，不联网。", muted = true))
            card.addView(ui.button("下载词汇表") { download() })
        }
        ui.card(column) { card ->
            card.addView(ui.button("开源许可与第三方说明") {
                val notices = assets.open("THIRD-PARTY.txt").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle("开源来源与许可说明").setMessage(notices).setPositiveButton("关闭", null).show()
            })
        }
        setContentView(ui.page("数据与关于", column))
    }

    /**
     * 下载两张释义表。表是公开数据、免鉴权，但本版还没配好地址与哈希时直接说明，
     * 不让用户看到一个必然失败的网络请求。
     */
    private fun download() {
        if (!GlossaryRelease.configured()) {
            AlertDialog.Builder(this)
                .setMessage("本版还没配好词汇表地址，请等下一次更新。没有词汇表时，建词与查词仍可手动输入。")
                .setPositiveButton("确定", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("下载中英两张词汇表（约 19MB），下载完成后校验完整性再安装。")
            .setNegativeButton("取消", null)
            .setPositiveButton("下载") { _, _ ->
                val worker = Executors.newSingleThreadExecutor()
                val downloader = GlossaryDownload(this, { false }) { }
                worker.execute {
                    val result = runCatching {
                        for (asset in GlossaryRelease.assets()) downloader.install(asset, GlossaryRelease.url(asset.name))
                    }
                    runOnUiThread {
                        val text = result.fold(
                            onSuccess = { "词汇表已下载，可以离线查词了" },
                            onFailure = { "词汇表下载失败：${it.message ?: "未知原因"}；已装好的表不受影响" })
                        AlertDialog.Builder(this).setMessage(text).setPositiveButton("确定", null).show()
                        worker.shutdown()
                        downloader.close()
                    }
                }
            }.show()
    }
}
