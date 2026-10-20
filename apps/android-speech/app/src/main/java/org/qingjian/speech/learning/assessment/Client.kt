//! 单次显式评测；实时节奏上传，有限等待、取消关闭连接，错误不带签名或密钥。
package org.qingjian.speech.learning.assessment

import android.util.Base64
import org.json.JSONObject
import org.qingjian.speech.learning.assessment.wire.Channel
import org.qingjian.speech.learning.assessment.wire.TlsChannel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class Client(private val open: (String)->Channel={TlsChannel(it)}) {
    @Volatile private var connection: Channel?=null
    @Volatile private var cancelled=false
    fun cancel(){cancelled=true;connection?.close()}
    private fun guard(){if(cancelled)throw CancellationException("评测已取消，录音保留")}
    fun test(profile: Profile) {profile.validate();guard();val channel=open(Signature.path(profile));connection=channel;try {channel.open();guard()} finally {channel.close();connection=null}}
    fun assess(profile: Profile, file: File, text: String, mode: String, progress: (String)->Unit={}): Result {
        profile.validate();Protocol.validate(text,mode);val pcm=Protocol.pcm(file);guard()
        val channel=open(Signature.path(profile));connection=channel
        val done=CountDownLatch(1);val result=AtomicReference<Result?>();val failure=AtomicReference<Exception?>()
        try {
            progress("正在连接讯飞评测…");channel.open();guard()
            val reader=Thread({
                try {
                    repeat(512) {
                        guard();val raw=channel.receive();check(raw.length<=262144)
                        val value=JSONObject(raw);val code=value.optInt("code",-1)
                        if(code!=0)error(when(code){11200,11201->"讯飞评测额度或服务权限不足，请检查控制台";48195->"讯飞不支持这段目标文本，请检查英文或改用例句评测（48195）";10105,10106,10107->"讯飞录音格式或参数不适用，请重新录音";else->"讯飞评测未完成（错误码 $code），请检查额度、服务权限或录音后重试"})
                        val data=value.optJSONObject("data")
                        if(data?.optInt("status")==2) {
                            val encoded=data.optString("data");check(encoded.length in 1..350000)
                            val xml=String(Base64.decode(encoded,Base64.DEFAULT),Charsets.UTF_8)
                            result.set(Parser.parse(xml));return@Thread
                        }
                    }
                    error("评测响应次数过多")
                } catch(error: Exception){failure.set(error)} finally {done.countDown()}
            },"qingjian-assessment-result").apply {isDaemon=true;start()}
            channel.send(Protocol.configuration(profile,text,mode))
            var offset=0;var previous=-1
            while(offset<pcm.size) {
                guard();failure.get()?.let {throw it};val count=minOf(1280,pcm.size-offset)
                channel.send(Protocol.frame(pcm.copyOfRange(offset,offset+count),first=offset==0));offset+=count
                val percent=(offset*100L/pcm.size).toInt()/20*20
                if(percent!=previous){previous=percent;progress("正在发送录音 · $percent%")}
                Thread.sleep(count*1000L/32000)
            }
            guard();channel.send(Protocol.frame(ByteArray(0),last=true));progress("录音已发送，正在分析发音…")
            val deadline=System.nanoTime()+30_000_000_000L
            while(!done.await(100,TimeUnit.MILLISECONDS)){guard();check(System.nanoTime()<deadline){"评测等待超时，录音保留；稍后可手动重试"}}
            guard();failure.get()?.let {throw it};val value=checkNotNull(result.get()) {"评测未返回结果，请稍后重试"}
            channel.close();reader.join(1000);return value
        } finally {channel.close();connection=null}
    }
    companion object {
        fun error(error: Throwable): String=when(error) {
            is CancellationException->"评测已取消，录音保留"
            is java.net.SocketTimeoutException->"连接或评测超时，录音保留，可手动重试"
            is IllegalArgumentException,is IllegalStateException->error.message?.take(180) ?: "评测配置或结果无效"
            else->"评测连接中断，请检查手机网络后重试；录音保留"
        }
    }
}
