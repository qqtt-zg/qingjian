//! 真实设备上的鉴权、WAV/分帧、XML边界与取消测试。
package org.qingjian.speech

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.qingjian.speech.learning.assessment.Client
import org.qingjian.speech.learning.assessment.Parser
import org.qingjian.speech.learning.assessment.Protocol
import org.qingjian.speech.learning.assessment.Signature
import org.qingjian.speech.learning.assessment.Credentials
import org.qingjian.speech.learning.assessment.wire.Channel
import org.qingjian.speech.learning.assessment.wire.Frames
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object AssessmentProtocolProbe {
    fun run(app:Context):String {
        val profile=AssessmentFixture.profile
        val path=Signature.path(profile,Date(0));check(path.startsWith("/v2/open-ise?"))
        val query=path.substringAfter('?').split('&').associate {it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='),"UTF-8")}
        check(query["date"]=="Thu, 01 Jan 1970 00:00:00 GMT" && query["host"]=="ise-api.xfyun.cn")
        val auth=String(Base64.decode(query["authorization"],Base64.DEFAULT));check(auth.contains("hmac-sha256") && auth.contains("host date request-line"))
        val data=JSONObject(Protocol.configuration(profile,"control","word"));check(data.getJSONObject("business").getString("category")=="read_word")
        check(data.getJSONObject("business").getString("text")=="\uFEFF[word]\ncontrol")
        check(JSONObject(Protocol.configuration(profile,"You can control the volume.","sentence")).getJSONObject("business").getString("text")=="\uFEFF[content]\nYou can control the volume.")
        check(JSONObject(Protocol.configuration(profile,"control system","word")).getJSONObject("business").getString("category")=="read_sentence")
        val output=ByteArrayOutputStream();Frames.write(output,"test".toByteArray());val frame=output.toByteArray()
        check(frame[0].toInt() and 255==129 && frame[1].toInt() and 128!=0)
        check(String(ByteArray(4){i->(frame[6+i].toInt() xor frame[2+i%4].toInt()).toByte()})=="test")
        check(Frames.read(ByteArrayInputStream(byteArrayOf(0x89.toByte(),1,7,0x01,2,65,66,0x80.toByte(),1,67))){check(it.contentEquals(byteArrayOf(7)))}=="ABC")
        check(runCatching {Frames.read(ByteArrayInputStream(byteArrayOf(0x81.toByte(),0x80.toByte()))){}}.isFailure)
        val parsed=Parser.parse(AssessmentFixture.XML);check(parsed.total==86.5 && parsed.fluency==82.0 && parsed.words.first().phones==listOf("t"))
        check(Parser.parse("<read_word total_score=\"0\"/>").accuracy==null)
        for(xml in listOf("<read_word/>","<read_word total_score=\"NaN\"/>","<read_word total_score=\"0\" is_rejected=\"true\"/>","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]><read_word total_score=\"4\"/>"))check(runCatching {Parser.parse(xml)}.isFailure)
        val credentials=Credentials(app);credentials.save(profile);check(credentials.load().same(profile))
        val raw=app.getSharedPreferences("oral_assessment_credentials",0).all.toString();check(!raw.contains(profile.apiSecret)&&!raw.contains(profile.apiKey)&&!raw.contains(profile.appId))
        val file=AssessmentFixture.audio(app);check(Protocol.pcm(file).size==16000)
        val queue=LinkedBlockingQueue<String>();val sent=mutableListOf<JSONObject>()
        val successful=object:Channel {
            override fun open()=Unit
            override fun send(text:String){val v=JSONObject(text);sent.add(v);if(v.getJSONObject("data").optInt("status")==2)queue.put(AssessmentFixture.response())}
            override fun receive()=queue.poll(5,TimeUnit.SECONDS) ?: error("fixture timeout")
            override fun close(){queue.offer("{}")}
        }
        val result=Client {successful}.assess(profile,file,"control","word")
        check(result.total==86.5 && sent.first().getJSONObject("data").getInt("status")==0)
        check(sent[1].getJSONObject("business").getInt("aus")==1 && sent.last().getJSONObject("business").getInt("aus")==4)
        val started=CountDownLatch(1);val done=CountDownLatch(1);var cancelled=false
        val wait=LinkedBlockingQueue<String>()
        val blocked=object:Channel {override fun open(){started.countDown()};override fun send(text:String)=Unit;override fun receive()=wait.take();override fun close(){wait.offer("{}")} }
        val client=Client {blocked}
        Thread {try {client.assess(profile,file,"control","word")}catch(_:Exception){cancelled=true}finally{done.countDown()}}.start()
        check(started.await(3,TimeUnit.SECONDS));client.cancel();check(done.await(5,TimeUnit.SECONDS)&&cancelled)
        credentials.clear();file.delete()
        return "PASS assessment signature/date, masked frames/fragment/ping/rejection, WAV/config sequencing, strict XML/null metrics, encrypted credentials, final result and cancellation"
    }
}
