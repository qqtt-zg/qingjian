//! 隔离模拟器验证真实分批分析、暂停检查点和服务商用量，不请求外网。
package org.qingjian.speech

import android.app.Instrumentation
import org.qingjian.speech.learning.LearningStore
import org.qingjian.speech.learning.archive.AnalysisJob
import org.qingjian.speech.learning.archive.Store
import org.qingjian.speech.learning.archive.Usage
import org.qingjian.speech.learning.ai.Client
import org.qingjian.speech.learning.ai.Profile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object ArchiveAnalysisProbe {
    fun run(test:Instrumentation):String {
        val app=test.targetContext;val book=LearningStore.get(app);book.clearLearning();for(i in 1..10)book.setMeaning(book.addWord("word-$i"),"中文笔记$i")
        val calls=AtomicInteger();val second=CountDownLatch(1);val gate=CountDownLatch(1)
        fun client()=Client {url->object:HttpURLConnection(url){
            val number=calls.incrementAndGet()
            override fun connect()=Unit
            override fun usingProxy()=false
            override fun disconnect(){if(number==2)gate.countDown()}
            override fun getResponseCode()=200
            override fun getOutputStream():ByteArrayOutputStream {if(number==2){second.countDown();check(gate.await(10,TimeUnit.SECONDS))};return ByteArrayOutputStream()}
            override fun getInputStream()=ByteArrayInputStream("""{"choices":[{"message":{"content":"优先巩固拼写，独立测验结果与提示后答对分开。"}}],"usage":{"prompt_tokens":120,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":50}}}""".toByteArray())
        }}
        var job=AnalysisJob(app,{client()},{Profile("https://analysis.invalid/v1","probe-model","fake-key")})
        job.start();check(second.await(10,TimeUnit.SECONDS));job.cancel();val ui=ProbeUi(test);ui.await("analysis paused"){!job.busy};check(job.progress().contains("1 /"));job.close()
        job=AnalysisJob(app,{client()},{Profile("https://analysis.invalid/v1","probe-model","fake-key")});job.start();ui.await("analysis completes"){!job.busy};check(job.progress().contains("完成"));check(Store(app).memories().single().getString("body").contains("拼写"))
        val rows=Usage(app).rows();check(rows.any {it.second.optInt("input")==120&&it.second.optInt("cached")==50&&it.second.isNull("reasoning")})
        job.close();book.clearLearning();return "PASS full-history snapshot batches, successful checkpoint survives pause/new job, complete coverage memory, actual token/cache subset and unknown reasoning, no external request"
    }
}
