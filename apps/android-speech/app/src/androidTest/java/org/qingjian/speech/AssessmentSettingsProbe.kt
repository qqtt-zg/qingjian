//! 评测配置页受控连接、明确进度、成功/取消反馈与旋转保留；不调用外网。
package org.qingjian.speech

import android.app.Instrumentation
import android.content.Intent
import android.widget.EditText
import org.qingjian.speech.learning.assessment.Client
import org.qingjian.speech.learning.assessment.Credentials
import org.qingjian.speech.learning.assessment.TestJob
import org.qingjian.speech.learning.assessment.SettingsActivity
import org.qingjian.speech.learning.assessment.wire.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AssessmentSettingsProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val ui=ProbeUi(test);Credentials(app).clear()
        var page=test.startActivitySync(Intent(app,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        val ready=AtomicBoolean();val calls=AtomicInteger()
        fun job()=SettingsActivity::class.java.getDeclaredField("job").apply {isAccessible=true}.get(page) as TestJob
        try {
            ui.await("assessment settings"){page.hasWindowFocus()}
            ui.click(page,"测试连接");check(ui.text(page.window.decorView).contains("请填写"))
            val profile=AssessmentFixture.profile
            ui.main {
                for((id,value) in listOf(SettingsActivity.ID_APP to profile.appId,SettingsActivity.ID_KEY to profile.apiKey,SettingsActivity.ID_SECRET to profile.apiSecret)) {
                    val field=page.findViewById<EditText>(id);check(!field.isSaveEnabled);field.setText(value)
                }
                check(page.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE!=0)
                TestJob::class.java.getDeclaredField("createClient").apply {isAccessible=true}.set(job(),{Client {object:Channel {
                    private val closed=AtomicBoolean()
                    override fun open(){calls.incrementAndGet();while(!ready.get()&&!closed.get())Thread.sleep(20);if(closed.get())throw java.util.concurrent.CancellationException()}
                    override fun send(text:String)=Unit
                    override fun receive()=""
                    override fun close(){closed.set(true)}
                }}})
            }
            ui.click(page,"保存评测配置");check(Credentials(app).load().same(profile))
            ui.click(page,"测试连接");ui.await("settings progress"){calls.get()==1&&ui.text(page.window.decorView).contains("正在验证")}
            ui.main {check(!page.findViewById<android.view.View>(SettingsActivity.ID_SAVE).isEnabled)}
            val old=job();val monitor=test.addMonitor(SettingsActivity::class.java.name,null,false)
            ui.main {page.recreate()};page=test.waitForMonitorWithTimeout(monitor,15000) as? SettingsActivity ?: error("settings recreate failed");test.removeMonitor(monitor)
            ui.await("retained connection test"){page.hasWindowFocus()&&job()===old};check(calls.get()==1)
            ready.set(true);ui.await("settings success"){ui.text(page.window.decorView).contains("连接成功，尚未上传录音")}
            ready.set(false);ui.click(page,"测试连接");ui.await("second settings test"){calls.get()==2};ui.click(page,"取消测试")
            ui.await("settings cancelled"){ui.text(page.window.decorView).contains("评测已取消")&&page.findViewById<android.view.View>(SettingsActivity.ID_SAVE).isEnabled}
            return "PASS assessment settings: invalid input, encrypted save, protected fields, explicit progress/disabled edits, one connection across recreation, success and cancellation without audio upload"
        } finally {ready.set(true);ui.main {page.finish()};Credentials(app).clear()}
    }
}
