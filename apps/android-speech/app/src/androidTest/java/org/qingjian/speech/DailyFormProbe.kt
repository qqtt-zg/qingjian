//! 独立模拟器验证每日听辨不泄露题词、拼写输入与软键盘下的固定主按钮。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.daily.PlanStore
import org.qingjian.speech.learning.daily.StudyActivity
import org.qingjian.speech.learning.quiz.Kind
import java.io.File

object DailyFormProbe {
    fun run(test: Instrumentation): String {
        val app=test.targetContext;val ui=ProbeUi(test);val book=LearningStore.get(app);book.clearLearning()
        val control=book.addWord("control");book.setMeaning(control,"控制")
        val help=book.addWord("help");book.setMeaning(help,"帮助")
        val plans=PlanStore(app);val plan=plans.create(DailyStoreProbe.proposal(control,help))
        plans.markLearned(plan.id,plans.items(plan.id).first {it.purpose=="LEARN"}.id)
        while(plans.get(plan.id)!!.phase=="PRACTICE") {val p=plans.get(plan.id)!!;val q=plans.questions(p.id,"PRACTICE")[p.cursor];plans.draft(p.id,q.question.english);plans.submit(p.id);plans.advance(p.id)}
        plans.skipAdjustment(plan.id);plans.advance(plan.id)
        var q=plans.questions(plan.id,"EXAM")[0];plans.draft(plan.id,q.question.english);plans.submit(plan.id);plans.advance(plan.id)
        val page=test.startActivitySync(Intent(app,StudyActivity::class.java).putExtra("plan_id",plan.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("daily listen focused") {page.hasWindowFocus()&&ui.button(page.window.decorView,"播放英文题目")!=null}
        q=plans.questions(plan.id,"EXAM")[1];check(q.listening)
        ui.click(page,"播放英文题目")
        ui.await("listening feedback",60000) {ui.text(page.window.decorView).contains("朗读完成")}
        check(!ui.text(page.window.decorView).contains(q.question.english,true)) {"听辨状态泄露了题目英文"}
        check(plans.questions(plan.id,"EXAM")[1].replays==1)
        ui.click(page,"每日选项：${q.question.meaning}");ui.click(page,"下一题")
        q=plans.questions(plan.id,"EXAM")[2];check(q.question.kind==Kind.SPELLING)
        fun input(view:View):EditText? {
            if(view is EditText)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)input(view.getChildAt(i))?.let {return it}
            return null
        }
        ui.main {
            val edit=checkNotNull(input(page.window.decorView));edit.requestFocus();edit.setText(q.question.english)
            (page.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit,InputMethodManager.SHOW_IMPLICIT)
        }
        ui.await("spelling ready") {page.findViewById<View>(R.id.dailyPrimary).isEnabled}
        ui.await("soft keyboard actually visible") {page.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        Thread.sleep(700);test.waitForIdleSync()
        ui.main {
            val primary=page.findViewById<View>(R.id.dailyPrimary);val visible=Rect();val frame=Rect()
            page.window.decorView.getWindowVisibleDisplayFrame(frame)
            check(primary.getGlobalVisibleRect(visible)&&visible.bottom<=frame.bottom+2&&visible.height()>=40*app.resources.displayMetrics.density)
        }
        val image=checkNotNull(test.uiAutomation.takeScreenshot());val dir=File(app.filesDir,"ui-review").apply {mkdirs()}
        File(dir,"daily-spelling-${app.resources.configuration.screenWidthDp}.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        ui.click(page,"完成小测");check(plans.get(plan.id)!!.phase=="RESULT"&&plans.results(plan.id).getInt("independent_correct")==3)
        ui.main {page.finish()}
        return "PASS daily listening: real TTS, masked status and replay count; spelling draft persisted, primary visible with keyboard, actual exam score"
    }
}
