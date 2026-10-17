//! 用户启动的前台接收会话，HTTPS 长轮询；通知栏可停止，退出即释放连接和唤醒锁。
package org.qingjian.speech

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverService : Service() {
    private val generation = AtomicLong()
    @Volatile private var receiving = false
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var destroyed = false
    private lateinit var player: SpeechPlayer
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val acknowledgements=Executors.newSingleThreadExecutor()
    private val ackQueued=AtomicBoolean(false)
    private val ackDirty=AtomicBoolean(false)
    @Volatile private var currentPair:Pairing?=null
    @Volatile private var controlRevision=-1L
    @Volatile private var remoteEnabled=true
    private lateinit var transfer:PackTransfer
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BundledVoice.initializeSelection(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("speech", "朗读连接", NotificationManager.IMPORTANCE_LOW))
        transfer=PackTransfer(this,{destroyed},{report(it)})
        player = SpeechPlayer(this, {
            if(!destroyed) getSharedPreferences("speech",0).edit().putString("last_playback",it).apply()
            report(it)
        }, { main.post { if (!receiving && !destroyed && player.isIdle()) stopSelf() } })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("正在准备朗读"))
        if (intent?.action == "test") {
            player.submit("Hello. English speech is ready.")
            return START_NOT_STICKY
        }
        if (intent?.action == "word") {
            val text = intent.getStringExtra("text").orEmpty()
            if(text.isNotBlank() && text.length <= 500) player.submit(text, learn = true)
            else if(!receiving) stopSelf()
            return START_NOT_STICKY
        }
        val pair = try { Pairing.parse(getSharedPreferences("speech",0).getString("url", "") ?: "") }
        catch (_: Exception) { report("配对地址无效，请重新粘贴"); stopSelf(); return START_NOT_STICKY }
        val run = generation.incrementAndGet()
        connection?.disconnect()
        transfer.close()
        transfer=PackTransfer(this,{destroyed || generation.get()!=run},{if(!destroyed && generation.get()==run) report(it)})
        controlRevision=-1L
        currentPair=pair
        receiving = true
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"qingjian:speech-session").apply { acquire() }
        }
        Thread({ receive(pair,run) }, "qingjian-receiver").start()
        return START_NOT_STICKY
    }

    private fun receive(pair: Pairing, run: Long) {
        var after: Long? = null
        var delay = 1500L
        while (!destroyed && generation.get() == run) {
            try {
                val path = after?.let { "/api/events?after=$it&control_after=$controlRevision" } ?: "/api/status"
                val response = fetch(pair,path,run)
                if (generation.get() != run || destroyed) break
                response.optJSONObject("control")?.let {applyControl(it,pair,run)}
                if (after == null) {
                    after = response.getLong("sequence")
                    org.qingjian.speech.update.UpdateClient.check(this)
                    report(if(remoteEnabled) "已连接，可锁屏；等待电脑点击喇叭" else "电脑已暂停朗读，连接保留")
                } else {
                    val next = response.getLong("sequence")
                    if (next > after) {
                        after = next
                        val text = response.optString("text")
                        if (text.isNotBlank() && text.length <= 500 && remoteEnabled) {
                            val time=java.text.DateFormat.getTimeInstance().format(java.util.Date())
                            getSharedPreferences("speech",0).edit().putString("last_receive","$time 已收到朗读请求（序号 $next）").apply()
                            runCatching { org.qingjian.speech.learning.LearningStore.get(this).record(text) }
                            player.submit(text, learn = true)
                        }
                    }
                }
                acknowledge()
                delay = 1500
            } catch (_: SecurityException) {
                if (generation.get() == run && !destroyed) main.post { if(generation.get() == run && !destroyed) { report("配对已失效，请粘贴电脑的新地址"); stopSelf() } }
                break
            } catch (_: Exception) {
                if (generation.get() != run || destroyed) break
                report("网络中断，正在重新连接…")
                try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
                delay = (delay * 2).coerceAtMost(15000)
            }
        }
    }

    private fun applyControl(control:JSONObject,pair:Pairing,run:Long) {
        val revision=control.getLong("revision")
        if(revision==controlRevision) return
        remoteEnabled=control.getBoolean("enabled")
        getSharedPreferences("speech",0).edit().putBoolean("remote_enabled",remoteEnabled).apply()
        if(!remoteEnabled) player.cancelPending()
        val pack=control.getString("pack")
        val speaker=control.getInt("speaker")
        require(speaker in 0..1023)
        if(pack!="builtin") {
            VoiceLibrary.directory(this,pack)
            try {
                transfer.install(pair,pack,fetch(pair,"/api/packs/$pack/meta",run))
            } catch(error:Exception) {
                if(!destroyed && generation.get()==run) {
                    report("音色传输失败（${error.javaClass.simpleName}），保留当前音色")
                    controlRevision=revision
                }
                return
            }
        }
        if(destroyed || generation.get()!=run) return
        val prefs=getSharedPreferences("speech",0)
        val changed=prefs.getString("pack","builtin")!=pack || prefs.getInt("voice",0)!=speaker
        prefs.edit().putString("pack",pack).putInt("voice",speaker).apply()
        controlRevision=revision
        if(changed) player.configure()
        if(!remoteEnabled) report("电脑已暂停朗读，连接保留")
    }

    private fun acknowledge() {
        if(destroyed || currentPair==null) return
        ackDirty.set(true)
        if(!ackQueued.compareAndSet(false,true)) return
        runCatching { acknowledgements.execute {
            try {
                while(ackDirty.getAndSet(false) && !destroyed) {
                    val pair=currentPair ?: break
                    val prefs=getSharedPreferences("speech",0)
                    val data=JSONObject().put("status",prefs.getString("status","已连接"))
                        .put("catalog",VoiceLibrary.catalog(this)).put("active_pack",prefs.getString("pack","builtin"))
                        .put("active_speaker",prefs.getInt("voice",0)).put("control_revision",controlRevision)
                    val http=URL(pair.base+"/api/device").openConnection() as HttpURLConnection
                    try {
                        http.instanceFollowRedirects=false;http.connectTimeout=5000;http.readTimeout=5000
                        http.requestMethod="POST";http.doOutput=true
                        http.setRequestProperty("Authorization","Bearer ${pair.token}")
                        http.setRequestProperty("Content-Type","application/json")
                        http.outputStream.use {it.write(data.toString().toByteArray(Charsets.UTF_8))}
                        http.responseCode
                    } catch(_:Exception) {} finally {http.disconnect()}
                }
            } finally {ackQueued.set(false);if(ackDirty.get() && !destroyed) acknowledge()}
        } }
    }

    private fun fetch(pair: Pairing, path: String, run: Long): JSONObject {
        val http = URL(pair.base + path).openConnection() as HttpURLConnection
        if (generation.get() != run || destroyed) { http.disconnect(); throw InterruptedException() }
        connection = http
        try {
            http.instanceFollowRedirects = false
            http.connectTimeout = 10000; http.readTimeout = 22000
            http.setRequestProperty("Authorization", "Bearer ${pair.token}")
            if (http.responseCode == 401) throw SecurityException()
            check(http.responseCode == 200)
            val bytes = http.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val block = ByteArray(2048)
                while(true) {
                    val count = input.read(block)
                    if(count < 0) break
                    check(buffer.size() + count <= 16384)
                    buffer.write(block,0,count)
                }
                buffer.toByteArray()
            }
            check(bytes.size <= 16384)
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { http.disconnect(); if (connection === http) connection = null }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,1,Intent(this,ReceiverService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"speech").setContentTitle("青简朗读").setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"停止",stop).build()).setOngoing(true).build()
    }

    private fun report(message: String) {
        if (destroyed) return
        getSharedPreferences("speech",0).edit().putString("status",message).apply()
        acknowledge()
        if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(1, notification(message))
        }
    }

    override fun onDestroy() {
        destroyed = true; receiving = false; generation.incrementAndGet()
        connection?.disconnect(); player.close()
        transfer.close();acknowledgements.shutdownNow();currentPair=null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        getSharedPreferences("speech",0).edit().putString("status","已停止").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
