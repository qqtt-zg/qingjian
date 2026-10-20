//! 旧跟读入口进入原词卡；权限失败、离页释放与三十秒自动保存。
package org.qingjian.speech

import android.Manifest
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.OralActivity
import org.qingjian.speech.learning.oral.Recorder
import org.qingjian.speech.learning.practice.PracticeStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OralProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val ui=ProbeUi(test);val book=LearningStore.get(app);book.clearLearning()
        val id=book.addWord("control");book.setMeaning(id,"控制");val practice=PracticeStore(app)
        val denied=object:ContextWrapper(app){override fun getApplicationContext()=this;override fun checkSelfPermission(permission:String)=PackageManager.PERMISSION_DENIED}
        val rejected=CountDownLatch(1);var noCapture=false
        Recorder(denied){capture,_->noCapture=capture==null;rejected.countDown()}.start()
        check(rejected.await(5,TimeUnit.SECONDS)&&noCapture)
        test.uiAutomation.grantRuntimePermission(app.packageName,Manifest.permission.RECORD_AUDIO)
        val monitor=test.addMonitor(WordStudyActivity::class.java.name,null,false)
        ui.main {app.startActivity(Intent(app,OralActivity::class.java).putExtra("word_id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        val page=test.waitForMonitorWithTimeout(monitor,15000) as? WordStudyActivity ?: error("legacy oral entry failed");test.removeMonitor(monitor)
        try {
            ui.await("inline oral entry"){page.hasWindowFocus()};val session=WorkspaceProbe.state(page).oral
            check(practice.recordings(id).isEmpty())
            ui.main {page.findViewById<android.widget.Button>(WordStudyActivity.ID_PRIMARY).performClick()}
            ui.await("real recording starts"){File(app.cacheDir,"oral").listFiles().orEmpty().any {it.length()>32044}}
            ui.await("30-second automatic save",40000){session.phase=="recorded"}
            val recording=practice.recordings(id).single();check(recording.duration in 500..30000 && recording.rating=="unreviewed")
            check(AudioFiles.file(app,recording.filename).isFile)
            ui.click(page,"还需练习");check(practice.recordings(id).single().rating=="again")
            ui.click(page,"重录");ui.await("second recording starts"){session.phase=="recording"}
            ui.main {page.finish()};ui.await("leave-page recording release"){!session.busy}
            check(practice.recordings(id).isNotEmpty()&&AudioFiles.file(app,recording.filename).isFile)
            return "PASS legacy oral entry enters same word card, permission failure safe, real 30-second auto-save, separate self-rating, leave-page microphone release and prior recording retained"
        } finally {ui.main {if(!page.isFinishing)page.finish()};book.clearLearning()}
    }
}
