//! 仅用于隔离模拟器：真实盲写/考试/档案与原生四标签，无真实联网请求。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.widget.EditText
import org.json.JSONObject
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.WordStudyActivity
import org.qingjian.speech.learning.archive.Store
import org.qingjian.speech.learning.archive.ProfileActivity
import org.qingjian.speech.learning.spelling.Session

object LearningV2Probe {
    fun run(test:Instrumentation):String {
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk")){"仅在隔离模拟器执行"}
        val app=test.targetContext;val book=LearningStore.get(app);book.clearLearning()
        val word=book.addWord("receive");book.setMeaning(word,"收到")
        val session=Session(app,word);session.draft="recieve";session.submit();check(!session.correct)
        session.reset();session.assist(2);session.draft="receive";session.submit();check(session.correct&&session.assisted)
        val archive=Store(app);check(archive.events().count {it.getString("kind")=="拼写"}==2)
        check(Session(app,word).submitted&&Session(app,word).assisted)
        val exam=org.qingjian.speech.learning.exam.Store(app);val initial=exam.create("全部词汇",10)
        initial.getJSONArray("questions").getJSONObject(0).put("answer","receive");exam.save(initial)
        check(exam.get(initial.getString("id"))!!.getJSONArray("questions").getJSONObject(0).getString("answer")=="receive")
        check(archive.events().size==2)
        check(runCatching {exam.create("全部词汇",5)}.isFailure)
        check(exam.submit(initial.getString("id")).getInt("correct")==1)
        exam.submit(initial.getString("id"));check(archive.events().size==3)
        val second=exam.create("全部词汇",10);check(exam.submit(second.getString("id")).getInt("correct")==0)
        check(archive.saveAnalysis("拼写顺序需要巩固",archive.revision()))
        archive.edit("history","我更重视盲写")
        check(!archive.saveAnalysis("不能覆盖用户纠正",archive.revision()))
        archive.edit("history","我更重视盲写",true);check(!archive.saveAnalysis("不能恢复用户删除",archive.revision()));check(archive.memories().isEmpty())
        val exported=org.qingjian.speech.learning.archive.Transfer.export(app)
        check(exported.getJSONObject("tables").has("learning_events"));check(!exported.toString().contains("api_key"))
        val ui=ProbeUi(test)
        fun visibleText(view:android.view.View):String {
            if(view.visibility!=android.view.View.VISIBLE)return ""
            return when(view){is android.widget.TextView->view.text.toString();is android.view.ViewGroup->(0 until view.childCount).joinToString("\n"){visibleText(view.getChildAt(it))};else->""}
        }
        val activity=test.startActivitySync(Intent(app,ProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.main {check(ui.text(activity.window.decorView).contains("每日参考 500 次"));check(ui.target(activity.window.decorView,"我的")!=null);check(ui.target(activity.window.decorView,"电脑来源")!=null)}
        fun screenshot(name:String){test.waitForIdleSync();val bitmap=checkNotNull(test.uiAutomation.takeScreenshot());val dir=java.io.File(app.filesDir,"ui-review").apply {mkdirs()};java.io.File(dir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        screenshot("learning-v2-my.png")
        ui.click(activity,"展开月历");ui.main {check(ui.target(activity.window.decorView,"收起月历")!=null)};ui.click(activity,"收起月历");ui.main {activity.finish()}
        Session(app,word).reset()
        val card=test.startActivitySync(Intent(app,WordStudyActivity::class.java).putExtra("word_id",word).putExtra("open_spelling",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.main {check(!visibleText(card.window.decorView).contains("receive")) {"盲写不应泄露英文答案"};card.finish()}
        val examPage=test.startActivitySync(Intent(app,org.qingjian.speech.learning.exam.ExamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.click(examPage,"开始小测");ui.main {val input=ui.target(examPage.window.decorView,"小测英文答案") as EditText;input.setText("receive");check(!visibleText(examPage.window.decorView).contains("本题答案"))}
        val draft=exam.latest()!!;check(draft.getJSONArray("questions").getJSONObject(0).getString("answer")=="receive");ui.main {examPage.finish()}
        val scannerMonitor=test.addMonitor(ScanActivity::class.java.name,null,false)
        val host=test.startActivitySync(Intent(app,org.qingjian.speech.learning.LearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.main {host.startActivity(Intent(host,ScanActivity::class.java))}
        val scanner=checkNotNull(test.waitForMonitorWithTimeout(scannerMonitor,10000));test.removeMonitor(scannerMonitor)
        ui.await("portrait scanner controls"){scanner.findViewById<android.view.View>(R.id.btnScanTorch)!=null}
        ui.main {check(scanner.requestedOrientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);val torch=scanner.findViewById<android.view.View>(R.id.btnScanTorch);check(torch.contentDescription=="打开手电筒");torch.performClick();check(torch.contentDescription=="关闭手电筒");scanner.finish();host.finish()}
        book.clearLearning();check(archive.events().isEmpty()&&archive.memories().isEmpty()&&exam.latest()==null)
        return "PASS v2: assisted spelling durable; independent draft and submit-only grading, blank and duplicate submission; correction/deletion precedence; readable backup excludes credentials; native My week/month calendar and four tabs; blind card hides answer; clear lifecycle"
    }
}
