//! 动态音色配置与目录；只加载安装到应用私有目录的模型数据。
package org.qingjian.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object VoiceLibrary {
    fun root(context: Context)=File(context.filesDir,"voices").apply { mkdirs() }
    fun directory(context: Context,id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) {"无效音色编号"}
        return File(root(context),id)
    }
    fun manifest(context: Context,id: String)=JSONObject(File(directory(context,id),"voicepack.json").readText())
    fun path(root: File,name: String): String {
        require(name.isNotBlank() && name.length<=240 && !name.contains('\\') && !name.contains(':') && !name.startsWith('/') && name.split('/').none {it.isEmpty() || it=="." || it==".."})
        val file=File(root,name).canonicalFile
        require(file.path.startsWith(root.canonicalPath+File.separator))
        return file.path
    }
    fun create(context: Context,id: String,voice: Int=0): OfflineTts {
        if(id=="builtin") return BundledVoice.create(context,voice)
        val root=directory(context,id)
        val data=manifest(context,id)
        val model=path(root,data.getString("model")); val tokens=path(root,data.getString("tokens"))
        val voices=data.optString("voices").let {if(it.isBlank()) "" else path(root,it)}
        val lexicon=data.optString("lexicon").let {if(it.isBlank()) "" else path(root,it)}
        val phonemes=data.optString("data_dir").let {if(it.isBlank()) "" else path(root,it)}
        require(File(model).isFile && File(tokens).isFile)
        val config=when(data.getString("engine")) {
            "kitten"->OfflineTtsModelConfig(kitten=OfflineTtsKittenModelConfig(model=model,tokens=tokens,voices=voices,dataDir=phonemes),numThreads=2)
            "vits"->OfflineTtsModelConfig(vits=OfflineTtsVitsModelConfig(model=model,tokens=tokens,lexicon=lexicon,dataDir=phonemes),numThreads=2)
            "kokoro"->OfflineTtsModelConfig(kokoro=OfflineTtsKokoroModelConfig(model=model,tokens=tokens,voices=voices,dataDir=phonemes,lexicon=lexicon,lang="en-us"),numThreads=2)
            else->throw IllegalArgumentException("不支持此音色引擎")
        }
        return OfflineTts(config=OfflineTtsConfig(model=config))
    }
    fun count(context: Context,id: String): Int = if(id=="builtin") 8 else runCatching {File(directory(context,id),"speakers.txt").readText().toInt().coerceIn(1,1024)}.getOrDefault(1)
    fun recordCount(context: Context,id: String,count: Int) {
        require(count in 1..1024)
        if(id!="builtin") File(directory(context,id),"speakers.txt").writeText(count.toString())
    }
    fun names(context: Context,id: String): Array<String> = if(id=="builtin") BundledVoice.labels else Array(count(context,id)) {"音色 ${it+1}"}
    fun catalog(context: Context): JSONArray {
        val result=JSONArray().put(JSONObject().put("id","builtin").put("speakers",8))
        root(context).listFiles()?.filter {it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) && File(it,"speakers.txt").isFile}?.take(99)?.forEach {
            result.put(JSONObject().put("id",it.name).put("speakers",count(context,it.name)))
        }
        return result
    }
}
