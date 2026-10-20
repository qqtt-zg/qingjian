//! 常驻条共用的连接操作；先验证配对，再保存并启动，扫码后不需要第二次连接。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.qingjian.speech.Pairing
import org.qingjian.speech.ReceiverService

class ConnectionControls(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("speech", 0)

    var permissionRequested = false
        private set

    fun connect(value: String): Boolean {
        val url = value.trim()
        if(runCatching { Pairing.parse(url) }.isFailure) {
            Toast.makeText(activity, "请扫描有效的青简配对二维码，原连接保留", Toast.LENGTH_LONG).show()
            return false
        }
        val previous = listOf("url", "status", "connection_status").associateWith { prefs.getString(it, null) }
        prefs.edit().putString("url", url).putString("status", "正在连接电脑…").putString("connection_status", "正在连接电脑…").apply()
        try {
            activity.startForegroundService(Intent(activity, ReceiverService::class.java).setAction("connect"))
        } catch(_: Exception) {
            prefs.edit().apply {
                previous.forEach { (key, value) -> if(value == null) remove(key) else putString(key, value) }
            }.apply()
            Toast.makeText(activity, "连接未启动，请回到前台重试", Toast.LENGTH_LONG).show()
            return false
        }
        requestNotificationsOnce()
        return true
    }

    fun reconnect() = connect(prefs.getString("url", "").orEmpty())

    fun stop() {
        activity.stopService(Intent(activity, ReceiverService::class.java))
        prefs.edit().putString("status", "已停止接收").putString("connection_status", "已停止接收").apply()
    }

    fun permissionFinished() { permissionRequested = false }

    private fun requestNotificationsOnce() {
        if(Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED || prefs.getBoolean("notifications_requested", false)) return
        runCatching {
            permissionRequested = true
            activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), NOTIFICATIONS)
            prefs.edit().putBoolean("notifications_requested", true).apply()
        }.onFailure { permissionRequested = false }
    }

    companion object { const val NOTIFICATIONS = 45 }
}
