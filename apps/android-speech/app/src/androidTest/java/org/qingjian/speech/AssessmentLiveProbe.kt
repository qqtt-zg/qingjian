//! 显式运行的真实接口联调，仅读取隔离设备临时配置并上传本机合成音频。
package org.qingjian.speech

import android.app.Instrumentation
import org.json.JSONObject
import org.qingjian.speech.learning.assessment.Client
import org.qingjian.speech.learning.assessment.Profile
import org.qingjian.speech.learning.oral.AudioFiles
import org.qingjian.speech.learning.oral.Wave
import java.io.File

object AssessmentLiveProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val input=File(app.filesDir,"xfyun-test.json")
        val data=try {JSONObject(input.readText())}finally {input.delete()}
        val profile=Profile(data.getString("app_id"),data.getString("api_key"),data.getString("api_secret"));profile.validate()
        val client=Client();client.test(profile)
        val engine=BundledVoice.create(app,0);val rows=mutableListOf<String>()
        try {
            for((reference,mode) in listOf("control" to "word","You can control the volume." to "sentence")) {
                val generated=engine.generate(reference,0,1f)
                val count=(generated.samples.size*16000L/generated.sampleRate).toInt()
                val pcm=ShortArray(count+16000) {i->
                    val sample=i-4000
                    if(sample<0||sample>=count)0 else {
                        val position=sample*generated.sampleRate/16000.0;val offset=position.toInt().coerceAtMost(generated.samples.lastIndex)
                        val next=(offset+1).coerceAtMost(generated.samples.lastIndex);val ratio=(position-offset).toFloat()
                        ((generated.samples[offset]*(1-ratio)+generated.samples[next]*ratio).coerceIn(-1f,1f)*30000).toInt().toShort()
                    }
                }
                val file=AudioFiles.temporary(app)
                try {
                    file.outputStream().use {out->out.write(Wave.header(pcm.size));for(value in pcm){out.write(value.toInt() and 255);out.write(value.toInt() shr 8 and 255)}}
                    val score=Client().assess(profile,file,reference,mode)
                    rows+="$mode: total=${score.total}, words=${score.words.size}, accuracy=${score.accuracy}, fluency=${score.fluency}, integrity=${score.completeness}"
                }finally {file.delete()}
            }
        }finally {engine.release();client.cancel()}
        return "PASS live Xfyun TLS authentication and synthetic-English word/sentence assessment (no microphone audio): "+rows.joinToString("; ")
    }
}
