//! 中文反馈与小结的独立音色设置，低频配置收进朗读设置。
package org.qingjian.speech

import android.app.Activity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.quiz.PreviewSpeech

class ChineseVoiceControls(private val activity:Activity) {
    private val ui=Views(activity)
    private val speech=PreviewSpeech(activity)
    val view=ui.column()
    private val status=ui.column()
    init {
        view.addView(ui.caption("中文反馈音色"))
        view.addView(Spinner(activity).apply {
            id=ID_VOICE;adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,ChineseVoices.labels)
            setSelection(ChineseVoices.index(activity));onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?)=Unit
                override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long){if(position!=ChineseVoices.index(activity)){speech.stop();ChineseVoices.save(activity,position)}}
            }
        })
        view.addView(ui.button("试听中文与英文") {speech.toggle("chinese-preview","这次练习已经完成。Can you control the volume? 可以再听一遍示范。")})
        view.addView(status)
        view.addView(ui.caption("中文解释和英文示范分别使用所选音色；共用内置模型，无需系统TTS设置。"))
    }
    fun resume(){status.removeAllViews();status.addView(speech.label())}
    fun close(){speech.close()}
    companion object {const val ID_VOICE=0x005a9501}
}
