//! 旧跟读入口兼容：选好词后进入同一张词卡，不再打开独立录音工作页。
package org.qingjian.speech.learning.oral

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.Views
import org.qingjian.speech.learning.WordStudyActivity

class OralActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val id=intent.getLongExtra("word_id",0)
        if(id>0){open(id);return}
        val ui=Views(this);val column=ui.column();val list=ui.column();val search=ui.input("搜索要跟读的词或短语")
        column.addView(ui.text("选择词条后，在词卡内听示范、录音、评测和练习。",14f,true));column.addView(search);column.addView(list)
        fun populate(){list.removeAllViews();for(word in LearningStore.get(this).words(search.text.toString()))list.addView(ui.button(word.english){open(word.id)})}
        search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit;override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=populate();override fun afterTextChanged(s:Editable?)=Unit})
        populate();setContentView(ui.page("选择跟读词汇",column))
    }
    private fun open(id:Long){startActivity(Intent(this,WordStudyActivity::class.java).putExtra("word_id",id).putExtra("open_oral",true));finish()}
    companion object {const val ID_RECORD=0x005a5001;const val ID_SAVE=0x005a5002;const val ID_STATUS=0x005a5003}
}
