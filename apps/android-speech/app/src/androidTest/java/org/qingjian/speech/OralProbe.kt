//! 隔离模拟器验证麦克风、保存回放、自评与离页释放；不使用真实用户录音或上传服务。
package org.qingjian.speech

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.widget.Button
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.OralActivity
import org.qingjian.speech.learning.oral.Recorder
import org.qingjian.speech.learning.practice.PracticeStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OralProbe {
    fun run(test: Instrumentation): String {
        val app = test.targetContext
        val store = LearningStore.get(app); store.clearLearning()
        val id = store.addWord("control"); store.setMeaning(id, "控制")
        val practice = PracticeStore(app)
        val ui = ProbeUi(test)
        val denied = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
        }
        val rejected = CountDownLatch(1)
        var noCapture = false
        Recorder(denied) { capture, _ -> noCapture = capture == null; rejected.countDown() }.start()
        check(rejected.await(5, TimeUnit.SECONDS) && noCapture)
        test.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = test.startActivitySync(Intent(app, OralActivity::class.java).putExtra("word_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OralActivity
        ui.await("oral visible") { activity.hasWindowFocus() }
        val cache = File(app.cacheDir, "oral")
        ui.main {
            check(ui.text(activity.window.decorView).contains("不会判断读音正误"))
            check(activity.findViewById<Button>(OralActivity.ID_RECORD).text.toString() == "开始录音")
            check(cache.listFiles().orEmpty().isEmpty())
            activity.findViewById<Button>(OralActivity.ID_RECORD).performClick()
        }
        ui.await("real microphone WAV samples") { cache.listFiles().orEmpty().any { it.length() > 32044 } }
        ui.main { activity.findViewById<Button>(OralActivity.ID_RECORD).performClick() }
        ui.await("clip available") { activity.findViewById<Button>(OralActivity.ID_SAVE) != null }
        ui.main { activity.findViewById<Button>(OralActivity.ID_SAVE).performClick() }
        val recording = practice.recordings(id).single()
        check(recording.duration >= 500 && recording.rating == "unreviewed")
        check(AudioFiles.file(app, recording.filename).isFile)
        ui.click(activity, "回放录音")
        ui.await("real MediaPlayer completion") { ui.text(activity.window.decorView).contains("回放结束") }
        ui.click(activity, "还需练习")
        check(practice.recordings(id).single().rating == "again")
        ui.main { check(ui.text(activity.window.decorView).contains("自动评估：未评估")) }
        ui.main { activity.findViewById<Button>(OralActivity.ID_RECORD).performClick() }
        ui.await("second capture started") { cache.listFiles().orEmpty().any { it.length() > 44 } }
        ui.main { activity.finish() }
        ui.await("cancelled capture removed") { cache.listFiles().orEmpty().isEmpty() }
        check(practice.recordings(id).size == 1)
        check(AudioFiles.file(app, recording.filename).isFile)
        val timed = test.startActivitySync(Intent(app, OralActivity::class.java).putExtra("word_id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OralActivity
        ui.await("timed recording page") { timed.hasWindowFocus() }
        ui.main { timed.findViewById<Button>(OralActivity.ID_RECORD).performClick() }
        ui.await("thirty-second automatic stop", 40000) { timed.findViewById<Button>(OralActivity.ID_SAVE) != null }
        ui.main { timed.findViewById<Button>(OralActivity.ID_SAVE).performClick() }
        check(practice.recordings(id).size == 2 && practice.recordings(id).all { it.duration in 500..30000 })
        ui.main { timed.finish() }
        store.clearLearning()
        check(!AudioFiles.file(app, recording.filename).exists())
        return "PASS oral UI: no automatic recording, real AudioRecord WAV, stop/save, MediaPlayer playback, separate self-rating/no fabricated score, leave-page cancellation, thirty-second auto-stop and private file cleanup"
    }
}
