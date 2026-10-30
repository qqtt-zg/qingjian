//! 常驻条与设置共用的连接操作；管理多电脑配对列表、旧单地址平滑迁移与连接启停。
package org.qingjian.speech.learning

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.qingjian.speech.Pairing
import org.qingjian.speech.ReceiverService

data class PairedDevice(val url: String, val host: String, val name: String, val lastConnected: Long = 0L) {
    fun toJson(): JSONObject = JSONObject()
        .put("url", url)
        .put("host", host)
        .put("name", name)
        .put("last_connected", lastConnected)

    companion object {
        fun fromJson(json: JSONObject): PairedDevice? {
            val url = json.optString("url", "").trim()
            if (runCatching { Pairing.parse(url) }.isFailure) return null
            val pair = Pairing.parse(url)
            val host = json.optString("host", "").ifBlank { pair.host }
            val name = json.optString("name", "").ifBlank { host }
            val lastConnected = json.optLong("last_connected", 0L)
            return PairedDevice(url, host, name, lastConnected)
        }
    }
}

class ConnectionControls(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("speech", 0)

    var permissionRequested = false
        private set

    init {
        migrateLegacy(prefs)
    }

    fun connect(value: String): Boolean {
        val url = value.trim()
        val pair = runCatching { Pairing.parse(url) }.getOrNull()
        if (pair == null) {
            Toast.makeText(activity, "请扫描有效的青简配对二维码，原连接保留", Toast.LENGTH_LONG).show()
            return false
        }
        val previous = listOf("url", "active", "status", "connection_status").associateWith { prefs.getString(it, null) }
        val list = loadPairings(prefs).toMutableList()
        val index = list.indexOfFirst { it.host == pair.host }
        val newEntry = PairedDevice(url, pair.host, pair.host, System.currentTimeMillis())
        if (index >= 0) {
            list[index] = newEntry
        } else {
            list.add(newEntry)
        }
        savePairings(prefs, list)
        prefs.edit()
            .putString("active", url)
            .putString("url", url)
            .putString("status", "正在连接电脑…")
            .putString("connection_status", "正在连接电脑…")
            .apply()
        try {
            activity.startForegroundService(Intent(activity, ReceiverService::class.java).setAction("connect"))
        } catch (_: Exception) {
            prefs.edit().apply {
                previous.forEach { (key, v) -> if (v == null) remove(key) else putString(key, v) }
            }.apply()
            Toast.makeText(activity, "连接未启动，请回到前台重试", Toast.LENGTH_LONG).show()
            return false
        }
        requestNotificationsOnce()
        return true
    }

    fun reconnect(): Boolean {
        val url = activeUrl(prefs)
        return if (url.isNotBlank()) connect(url) else false
    }

    fun switchActive(url: String): Boolean {
        val target = url.trim()
        if (runCatching { Pairing.parse(target) }.isFailure) return false
        return connect(target)
    }

    fun removePairing(url: String) {
        val list = loadPairings(prefs).filterNot { it.url == url }
        savePairings(prefs, list)
        val active = activeUrl(prefs)
        if (active == url) {
            if (list.isNotEmpty()) {
                switchActive(list.first().url)
            } else {
                stop()
                prefs.edit().remove("active").remove("url").apply()
            }
        }
    }

    fun getPairings(): List<PairedDevice> = loadPairings(prefs)

    fun getActiveUrl(): String = activeUrl(prefs)

    fun stop() {
        activity.stopService(Intent(activity, ReceiverService::class.java))
        prefs.edit().putString("status", "已停止接收").putString("connection_status", "已停止接收").apply()
    }

    fun permissionFinished() { permissionRequested = false }

    private fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED || prefs.getBoolean("notifications_requested", false)) return
        runCatching {
            permissionRequested = true
            activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), NOTIFICATIONS)
            prefs.edit().putBoolean("notifications_requested", true).apply()
        }.onFailure { permissionRequested = false }
    }

    companion object {
        const val NOTIFICATIONS = 45

        fun activeUrl(prefs: SharedPreferences): String {
            migrateLegacy(prefs)
            return prefs.getString("active", "")?.ifBlank { prefs.getString("url", "").orEmpty() }.orEmpty()
        }

        fun loadPairings(prefs: SharedPreferences): List<PairedDevice> {
            migrateLegacy(prefs)
            val raw = prefs.getString("pairings", null) ?: return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val list = mutableListOf<PairedDevice>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                PairedDevice.fromJson(item)?.let { list.add(it) }
            }
            return list
        }

        fun savePairings(prefs: SharedPreferences, pairings: List<PairedDevice>) {
            val array = JSONArray()
            pairings.forEach { array.put(it.toJson()) }
            prefs.edit().putString("pairings", array.toString()).apply()
        }

        fun migrateLegacy(prefs: SharedPreferences) {
            if (!prefs.contains("pairings") && prefs.contains("url")) {
                val oldUrl = prefs.getString("url", "")?.trim().orEmpty()
                if (oldUrl.isNotBlank() && runCatching { Pairing.parse(oldUrl) }.isSuccess) {
                    val pair = Pairing.parse(oldUrl)
                    val device = PairedDevice(oldUrl, pair.host, pair.host, System.currentTimeMillis())
                    val array = JSONArray().put(device.toJson())
                    prefs.edit()
                        .putString("pairings", array.toString())
                        .putString("active", oldUrl)
                        .apply()
                }
            }
        }
    }
}
