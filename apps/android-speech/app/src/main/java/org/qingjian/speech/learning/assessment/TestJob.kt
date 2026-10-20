//! 配置连接测试可跨旋转保留，不上传录音，状态回调与 Activity 分离。
package org.qingjian.speech.learning.assessment

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class TestJob(private val createClient:()->Client={Client()}) {
    var draft=Profile("","","")
    var busy=false
        private set
    var notice=""
        private set
    var changed:(()->Unit)?=null
    private val main=Handler(Looper.getMainLooper())
    private val executor=Executors.newSingleThreadExecutor()
    private var client: Client?=null
    private var closed=false
    fun start(profile: Profile) {
        if(busy || closed)return
        try {profile.validate()} catch(error: Exception){notice=Client.error(error);changed?.invoke();return}
        draft=profile;busy=true;notice="正在验证评测服务连接…";changed?.invoke()
        val request=createClient();client=request
        executor.execute {
            val result=runCatching {request.test(profile)}
            main.post {busy=false;client=null;notice=if(result.isSuccess)"连接成功，尚未上传录音；保存后可返回词卡评测。" else Client.error(checkNotNull(result.exceptionOrNull()));if(!closed)changed?.invoke()}
        }
    }
    fun cancel(){client?.cancel()}
    fun close(){closed=true;changed=null;cancel();executor.shutdown()}
}
