//! 内置模型首次复制到应用私有目录；eSpeak 数据必须使用真实文件路径。
package org.qingjian.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import java.io.File

object BundledVoice {
    val labels = arrayOf("男声 1", "女声 1 · af_heart", "男声 2", "女声 2", "男声 3", "女声 3", "男声 4", "女声 4")

    fun engineKey(voice: Int): String = if(voice==1) "kokoro-heart" else "kitten"

    fun speakerId(voice: Int): Int = if(voice==1) 3 else voice

    fun initializeSelection(context: Context) {
        val prefs=context.getSharedPreferences("speech",0)
        if(!prefs.contains("voice")) prefs.edit().putInt("voice",1).apply()
    }

    fun create(context: Context, voice: Int=0): OfflineTts {
        val heart=voice==1
        val name = if(heart) "kokoro-multi-lang-v1_0" else "kitten-nano-en-v0_1-fp16"
        val root = File(context.filesDir,name)
        if (!File(root,"ready-v1").exists()) {
            copy(context,name,root)
            File(root,"ready-v1").writeText("1")
        }
        if(heart) return OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(
            kokoro=OfflineTtsKokoroModelConfig(model=File(root,"model.onnx").path,
                voices=File(root,"voices.bin").path, tokens=File(root,"tokens.txt").path,
                lexicon=File(root,"lexicon-us-en.txt").path,
                dataDir=File(root,"espeak-ng-data").path,lang="en-us"),numThreads=2)))
        return OfflineTts(config=OfflineTtsConfig(model=OfflineTtsModelConfig(
            kitten=OfflineTtsKittenModelConfig(model=File(root,"model.fp16.onnx").path,
                voices=File(root,"voices.bin").path, tokens=File(root,"tokens.txt").path,
                dataDir=File(root,"espeak-ng-data").path),numThreads=2)))
    }
    private fun copy(context: Context, asset: String, target: File) {
        val children = context.assets.list(asset) ?: emptyArray()
        if(children.isNotEmpty()) {
            check(target.isDirectory || target.mkdirs())
            children.forEach { copy(context,"$asset/$it",File(target,it)) }
        } else {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}
