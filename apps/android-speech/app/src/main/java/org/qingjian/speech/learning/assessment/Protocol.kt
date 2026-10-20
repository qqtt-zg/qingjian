//! 讯飞参数和音频分帧；目标文本与录音一次绑定，仅支持英语跟读。
package org.qingjian.speech.learning.assessment

import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    fun validate(text: String, mode: String) {
        require(mode in listOf("word","sentence") && text.length in 1..500 && text.any {it in 'A'..'Z'||it in 'a'..'z'} && text.none {it.code<32}) {"跟读目标需要有效的英文单词或句子（最多500字）"}
    }
    fun configuration(profile: Profile, text: String, mode: String): String {
        validate(text,mode)
        val category=if(mode=="word" && text.trim().split(Regex("\\s+")).size==1)"read_word" else "read_sentence"
        val marker=if(category=="read_word")"[word]" else "[content]"
        return JSONObject().put("common",JSONObject().put("app_id",profile.appId))
            .put("business",JSONObject().put("sub","ise").put("ent","en_vip").put("category",category)
                .put("cmd","ssb").put("text","\uFEFF$marker\n$text").put("tte","utf-8").put("rstcd","utf8").put("ttp_skip",true)
                .put("aue","raw").put("auf","audio/L16;rate=16000").put("rst","entirety").put("ise_unite","1")
                .put("extra_ability","multi_dimension;syll_phone_err_msg").put("plev","0"))
            .put("data",JSONObject().put("status",0)).toString()
    }
    fun frame(bytes: ByteArray, first: Boolean=false, last: Boolean=false): String = JSONObject()
        .put("business",JSONObject().put("cmd","auw").put("aus",if(last)4 else if(first)1 else 2))
        .put("data",JSONObject().put("status",if(last)2 else 1).put("data",Base64.encodeToString(bytes,Base64.NO_WRAP))).toString()

    fun pcm(file: File): ByteArray {
        require(file.isFile && file.length() in 16044..960044) {"录音文件无效，请重新录音"}
        val bytes=file.readBytes();val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes,0,4,Charsets.US_ASCII)=="RIFF" && String(bytes,8,4,Charsets.US_ASCII)=="WAVE" &&
            String(bytes,12,4,Charsets.US_ASCII)=="fmt " && String(bytes,36,4,Charsets.US_ASCII)=="data" &&
            data.getInt(16)==16 && data.getShort(20).toInt()==1 && data.getShort(22).toInt()==1 && data.getInt(24)==16000 &&
            data.getShort(34).toInt()==16 && data.getInt(40)==bytes.size-44 && (bytes.size-44)%2==0) {"录音需为16kHz单声道PCM16 WAV"}
        return bytes.copyOfRange(44,bytes.size)
    }
}
