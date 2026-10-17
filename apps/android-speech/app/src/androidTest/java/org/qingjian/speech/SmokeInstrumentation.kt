//! 设备侧验证真实模型合成、PCM 增益边界与配对地址校验。
package org.qingjian.speech

import android.app.Instrumentation
import android.os.Bundle
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject

class SmokeInstrumentation : Instrumentation() {
    private var probe=false
    private var playback=false
    private var network: String?=null
    private var words=false
    private var controlUrl:String?=null
    private var controlPack=""
    private var heart=false
    private var shortAudio=false
    private var learning=false
    private var updates=false
    private var settings=false
    private var rate=false
    private var toolbar=false
    private var aiConnection=false
    private var study=false
    private var migration=false
    private var practice=false
    private var quiz=false
    private var oral=false
    private var transfer=false
    private var downloadUi=false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); downloadUi=arguments?.getString("download_ui")=="true"; transfer=arguments?.getString("transfer")=="true"; migration=arguments?.getString("migration")=="true"; practice=arguments?.getString("practice")=="true"; quiz=arguments?.getString("quiz")=="true"; oral=arguments?.getString("oral")=="true"; study=arguments?.getString("study")=="true"; aiConnection=arguments?.getString("ai_connection")=="true"; rate=arguments?.getString("rate")=="true"; toolbar=arguments?.getString("toolbar")=="true"; settings=arguments?.getString("settings")=="true"; updates=arguments?.getString("updates")=="true"; learning=arguments?.getString("learning")=="true"; shortAudio=arguments?.getString("short_audio")=="true"; heart=arguments?.getString("heart")=="true"; probe=arguments?.getString("voices")=="true"; words=arguments?.getString("words")=="true"; playback=arguments?.getString("playback")=="true"; network=arguments?.getString("network"); controlUrl=arguments?.getString("control");controlPack=arguments?.getString("pack") ?: ""; start() }
    override fun onStart() {
        val result = Bundle()
        try {
            if(downloadUi) {result.putString("stream",DownloadUiProbe.run(this));finish(-1,result);return}
            if(transfer) {result.putString("stream",TransferProbe.run(this));finish(-1,result);return}
            if(migration) {result.putString("stream",MigrationProbe.run(this));finish(-1,result);return}
            if(practice) {result.putString("stream",PracticeProbe.run(this));finish(-1,result);return}
            if(quiz) {result.putString("stream",QuizProbe.run(this));finish(-1,result);return}
            if(oral) {result.putString("stream",OralProbe.run(this));finish(-1,result);return}
            if(study) {result.putString("stream",StudyProbe.run(this));finish(-1,result);return}
            if(aiConnection) {result.putString("stream",AiConnectionProbe.run(this));finish(-1,result);return}
            if(rate) {result.putString("stream",RateProbe.run(targetContext));finish(-1,result);return}
            if(toolbar) {result.putString("stream",ToolbarProbe.run(this));finish(-1,result);return}
            if(settings) {result.putString("stream",SettingsProbe.run(this));finish(-1,result);return}
            if(updates) {result.putString("stream",UpdateProbe.run(this));finish(-1,result);return}
            if(learning) {result.putString("stream",LearningProbe.run(this));finish(-1,result);return}
            if(shortAudio) {result.putString("stream",ShortAudioProbe.run(targetContext));finish(-1,result);return}
            if(heart) {result.putString("stream",HeartProbe.run(targetContext));finish(-1,result);return}
            controlUrl?.let { url ->
                startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                result.putString("stream",ControlProbe.run(targetContext,url,controlPack));finish(-1,result);return
            }
            if(probe || words) { result.putString("stream",VoiceProbe.measure(targetContext,words));finish(-1,result);return }
            network?.let { url ->
                startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                result.putString("stream",NetworkProbe.run(targetContext,url));finish(-1,result);return
            }
            if(playback) {
                startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                result.putString("stream",PlaybackProbe.run(targetContext));finish(-1,result);return
            }
            val engine = BundledVoice.create(targetContext)
            val audio = try { engine.generate("Hello. English speech is ready.",0,1f) } finally { engine.release() }
            check(audio.samples.isNotEmpty() && audio.sampleRate > 0)
            check(audio.samples.any { it != 0f })
            val pcm = Pcm.normalize(floatArrayOf(0f,0.01f,-0.01f,1f,-1f,Float.NaN),2f)
            check(pcm.all { it.toInt() in -32000..32000 })
            check(pcm[0] == 0.toShort() && pcm.last() == 0.toShort())
            val quiet=FloatArray(1000) { if(it%2==0) 0.001f else -0.001f }
            val quietOutput=Pcm.normalize(quiet,4f)
            check(quietOutput.all { kotlin.math.abs(it.toInt()) > 4000 }) { "Quiet speech gain is still capped too low" }
            check(Pcm.normalize(floatArrayOf(Float.POSITIVE_INFINITY),4f)[0]==0.toShort())
            val pairing = Pairing.parse("https://example.com/#"+"a".repeat(32))
            check(pairing.base == "https://example.com")
            check(runCatching { Pairing.parse("http://example.com/#"+"a".repeat(32)) }.isFailure)
            check(runCatching { Pairing.parse("https://example.com/") }.isFailure)
            val fixture=JSONObject(context.assets.open("qr-fixture.json").bufferedReader().use { it.readText() })
            val rows=fixture.getJSONArray("rows")
            val width=(rows.length()+8)*4
            val pixels=IntArray(width*width) { index ->
                val x=index%width/4-4; val y=index/width/4-4
                if(x in 0 until rows.length() && y in 0 until rows.length() && rows.getString(y)[x]=='1') 0xff000000.toInt() else -1
            }
            val decoded=QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,width,pixels)))).text
            check(decoded==fixture.getString("url")) { "Computer-generated QR did not decode intact" }
            val activity=startActivitySync(android.content.Intent(targetContext,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val prefs = targetContext.getSharedPreferences("speech",0)
            fun findVoice(view: android.view.View): android.widget.Spinner? {
                if(view is android.widget.Spinner) return view
                if(view is android.view.ViewGroup) for(i in 0 until view.childCount) {
                    findVoice(view.getChildAt(i))?.let {return it}
                }
                return null
            }
            runOnMainSync { checkNotNull(findVoice(activity.window.decorView)).setSelection(7) }
            waitForIdleSync()
            check(prefs.getInt("voice",-1)==7) { "Voice choice was not persisted" }
            val previous=prefs.getString("url","")
            runOnMainSync { activity.acceptScan("javascript:invalid"); activity.acceptScan(null) }
            check(prefs.getString("url","")==previous) { "Invalid/cancelled QR replaced saved pairing" }
            prefs.edit().putString("status","service test").commit()
            targetContext.startForegroundService(android.content.Intent(targetContext,ReceiverService::class.java).setAction("test"))
            val deadline = System.nanoTime()+60_000_000_000L
            var played = false
            var stopped = false
            while(System.nanoTime()<deadline) {
                val status = prefs.getString("status","") ?: ""
                if(status.startsWith("正在朗读")) played = true
                if(status=="已停止") { stopped=true; break }
                check(!status.contains("失败") && !status.contains("无法播放")) { status }
                Thread.sleep(20)
            }
            check(played && stopped) { "Service playback/cleanup did not complete" }
            check(prefs.getString("last_playback","")?.contains("女声 4")==true) { "Selected voice playback diagnostic was lost during cleanup" }
            runOnMainSync { activity.acceptScan(decoded) }
            check(prefs.getString("url","")==decoded) { "Valid QR was not saved for connection" }
            targetContext.stopService(android.content.Intent(targetContext,ReceiverService::class.java))
            result.putString("stream","PASS offline synthesis, voice selection persistence, quiet PCM gain/bounds, QR decode/pairing, foreground audio playback and cleanup")
            finish(-1,result)
        } catch (error: Throwable) { result.putString("stream","FAIL: ${error.javaClass.simpleName}: ${error.message}"); finish(0,result) }
    }
}
