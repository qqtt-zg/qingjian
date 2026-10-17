//! 应用更新入口；下载与检查在工作线程执行，安装由用户主动打开系统界面。
package org.qingjian.speech.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import org.qingjian.speech.learning.Views

class UpdateActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var message: TextView

    private lateinit var checkButton: Button

    private lateinit var actionButton: Button

    private lateinit var retryButton: Button

    private lateinit var pauseButton: Button

    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("app_update", 0)
            val data = UpdateClient.metadata(this@UpdateActivity)
            message.text = prefs.getString("status", "连接电脑后可检查更新") +
                (data?.let { "\n目标版本：${it.optString("version_name")}（构建 ${it.optLong("version_code")}）\n安装包大小：%.1f MB".format(it.optLong("size") / 1048576.0) } ?: "")
            checkButton.isEnabled = !UpdateClient.busy.get()
            actionButton.isEnabled = !UpdateClient.busy.get() && data != null &&
                data.optLong("version_code") > UpdateClient.code(UpdateClient.installed(this@UpdateActivity))
            val ready = UpdateClient.ready(this@UpdateActivity)
            val resumable = UpdateClient.resumable(this@UpdateActivity)
            actionButton.text = when { UpdateClient.downloading.get() -> "下载中…"; ready -> "安装更新"; resumable -> "继续下载"; else -> "下载更新" }
            retryButton.text = if(ready) "重新下载安装包" else "从头重新下载"
            retryButton.visibility = if (ready || resumable) android.view.View.VISIBLE else android.view.View.GONE
            retryButton.isEnabled = actionButton.isEnabled
            pauseButton.visibility = if(UpdateClient.downloading.get()) android.view.View.VISIBLE else android.view.View.GONE
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = Views(this)
        val column = ui.column()
        val current = UpdateClient.installed(this)
        column.addView(ui.text("当前版本 ${current.versionName}（构建 ${UpdateClient.code(current)}）", muted = true))
        column.addView(ui.text("通过已配对的电脑获取更新。下载完成后，由你确认安装；请保持电脑连接服务在线。", muted = true))
        message = ui.text(""); column.addView(message)
        checkButton = ui.button("检查更新") { UpdateClient.check(this) }; column.addView(checkButton)
        actionButton = ui.button("下载更新", true) {
            if (UpdateClient.ready(this)) install() else UpdateClient.download(this)
        }; column.addView(actionButton)
        retryButton = ui.button("重新下载安装包") { UpdateClient.download(this, restart = true) }.apply { visibility = android.view.View.GONE }
        column.addView(retryButton)
        pauseButton = ui.button("暂停下载") { UpdateClient.pauseDownload() }.apply { visibility = android.view.View.GONE }
        column.addView(pauseButton)
        setContentView(ui.page("应用更新", column))
    }

    private fun install() {
        if (!packageManager.canRequestPackageInstalls()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onSuccess { getSharedPreferences("app_update", 0).edit().putString("status", "允许安装未知应用后，返回这里再点安装更新").apply() }
                .onFailure { getSharedPreferences("app_update", 0).edit().putString("status", "请在系统设置中允许青简安装未知应用").apply() }
            return
        }
        UpdateClient.verifyForInstall(this) { file ->
            if (isFinishing || isDestroyed || file == null) return@verifyForInstall
            runCatching {
                val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { getSharedPreferences("app_update", 0).edit().putString("status", "无法打开安装界面，请检查系统安装权限后重试").apply() }
        }
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }

    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
