//! 低频评测设置独立页面；凭据不进入 Activity 持久状态或安装包。
package org.qingjian.speech.learning.assessment

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import org.qingjian.speech.learning.Views

class SettingsActivity: Activity() {
    private lateinit var job: TestJob
    private lateinit var appId: EditText
    private lateinit var apiKey: EditText
    private lateinit var apiSecret: EditText
    private lateinit var test: Button
    private lateinit var save: Button
    private lateinit var cancel: Button
    private lateinit var clear: Button
    private lateinit var spinner: ProgressBar
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        job=(lastNonConfigurationInstance as? TestJob) ?: TestJob().apply {draft=runCatching {Credentials(this@SettingsActivity).load()}.getOrDefault(Profile("","",""))}
        val ui=Views(this);val body=ui.column()
        body.addView(ui.title("讯飞发音评测"))
        body.addView(ui.text("为单词和英文例句的跟读提供发音反馈。手机直接联网评测，无需电脑在线。",14f,true))
        appId=ui.input("AppID",job.draft.appId).apply {id=ID_APP}
        apiKey=ui.input("APIKey",job.draft.apiKey).apply {id=ID_KEY}
        apiSecret=ui.input("APISecret",job.draft.apiSecret).apply {id=ID_SECRET}
        for((name,field) in listOf("AppID" to appId,"APIKey" to apiKey,"APISecret" to apiSecret)) {
            body.addView(ui.caption(name));field.isSaveEnabled=false;field.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            if(field!==appId)field.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            body.addView(field)
        }
        body.addView(ui.caption("从讯飞控制台「语音评测（流式版）」获取。凭据加密保存在本机，独立于文字 AI 配置。"))
        spinner=ProgressBar(this).apply {isIndeterminate=true;contentDescription="正在测试评测连接"};body.addView(spinner)
        status=ui.text("").apply {id=ID_STATUS;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};body.addView(status)
        test=ui.button("测试连接") {job.start(profile())}.apply {id=ID_TEST};body.addView(test)
        cancel=ui.button("取消测试") {job.cancel()};body.addView(cancel)
        save=ui.button("保存评测配置",true) {
            runCatching {Credentials(this).save(profile())}.onSuccess {job.draft=profile();status.text="评测配置已保存，可以返回当前词条继续跟读。"}
                .onFailure {status.text=Client.error(it)}
        }.apply {id=ID_SAVE};body.addView(save)
        clear=ui.button("清除评测配置") {
            AlertDialog.Builder(this).setMessage("清除手机保存的讯飞凭据？录音和已保存评分保留。")
                .setNegativeButton("取消",null).setPositiveButton("清除") {_,_->Credentials(this).clear();job.draft=Profile("","","");appId.setText("");apiKey.setText("");apiSecret.setText("");status.text="已清除评测配置"}.show()
        };body.addView(clear)
        body.addView(ui.caption("只有点击「评估发音」时才上传所选录音和目标英文，会使用服务商额度。连接测试仅验证连接，完整评测以实际录音结果为准。"))
        setContentView(ui.page("口语评测",body));job.changed={refresh()};refresh()
    }
    private fun profile()=Profile(appId.text.toString().trim(),apiKey.text.toString().trim(),apiSecret.text.toString().trim())
    private fun refresh() {status.text=job.notice;spinner.visibility=if(job.busy)View.VISIBLE else View.GONE;cancel.visibility=if(job.busy)View.VISIBLE else View.GONE;for(view in listOf(appId,apiKey,apiSecret,test,save,clear))view.isEnabled=!job.busy}
    @Deprecated("保留连接测试与内存草稿") override fun onRetainNonConfigurationInstance(): Any {job.draft=profile();return job}
    override fun onDestroy(){job.changed=null;if(!isChangingConfigurations)job.close();super.onDestroy()}
    companion object {const val ID_APP=0x005a9101;const val ID_KEY=0x005a9102;const val ID_SECRET=0x005a9103;const val ID_STATUS=0x005a9104;const val ID_TEST=0x005a9105;const val ID_SAVE=0x005a9106}
}
