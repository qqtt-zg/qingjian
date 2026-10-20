//! 评测协议测试使用的合成音频与受控返回，不调用用户的服务。
package org.qingjian.speech

import android.content.Context
import org.json.JSONObject
import org.qingjian.speech.learning.assessment.Profile
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.Wave
import java.io.File

object AssessmentFixture {
    val profile get()=Profile("test1234","a".repeat(32),"b".repeat(32))
    const val XML="<xml_result><read_sentence total_score=\"86.5\" accuracy_score=\"87\" fluency_score=\"82\" integrity_score=\"100\" is_rejected=\"false\"><sentence><word content=\"control\" total_score=\"65\" dp_message=\"0\"><syll serr_msg=\"1\"><phone content=\"t\" dp_message=\"16\"/></syll></word><word content=\"volume\" total_score=\"90\" dp_message=\"0\"/></sentence></read_sentence></xml_result>"
    fun response()=JSONObject().put("code",0).put("data",JSONObject().put("status",2).put("data",android.util.Base64.encodeToString(XML.toByteArray(),android.util.Base64.NO_WRAP))).toString()
    fun audio(app:Context):File {
        val file=AudioFiles.temporary(app)
        file.outputStream().use {out->out.write(Wave.header(8000));repeat(8000){i->val value=(kotlin.math.sin(i*.1)*1000).toInt();out.write(value and 255);out.write((value shr 8) and 255)}}
        return file
    }
}
