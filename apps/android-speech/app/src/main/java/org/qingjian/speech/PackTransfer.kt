//! 配对 HTTPS 音色下载、哈希验证和受限解压；只在完成后原子安装。
package org.qingjian.speech

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONObject

class PackTransfer(private val context: Context,private val cancelled: ()->Boolean,private val progress:(String)->Unit) {
    @Volatile private var connection:HttpURLConnection?=null
    fun close() { connection?.disconnect() }
    fun install(pair:Pairing,id:String,metadata:JSONObject) {
        val target=VoiceLibrary.directory(context,id)
        if(File(target,"voicepack.json").isFile) return
        require(metadata.getString("sha256")==id)
        val size=metadata.getLong("size"); val expanded=metadata.getLong("expanded")
        require(size in 1..536870912L && expanded in 1..805306368L)
        check(context.filesDir.usableSpace>size+expanded+32*1024*1024) {"存储空间不足"}
        val archive=File.createTempFile("voice-", ".part",context.cacheDir)
        val staging=File(VoiceLibrary.root(context),".staging-"+UUID.randomUUID()).apply {check(mkdir())}
        try {
            val http=URL(pair.base+"/api/packs/"+id).openConnection() as HttpURLConnection
            connection=http
            try {
                http.instanceFollowRedirects=false; http.connectTimeout=10000;http.readTimeout=30000
                http.setRequestProperty("Authorization","Bearer ${pair.token}")
                check(http.responseCode==200 && http.contentLengthLong==size)
                val hash=MessageDigest.getInstance("SHA-256")
                var downloaded=0L;var shown=-1
                http.inputStream.use {input-> archive.outputStream().use {output->
                    val buffer=ByteArray(65536)
                    while(true) {
                        check(!cancelled()) {"已取消传输"}
                        val read=input.read(buffer);if(read<0) break
                        downloaded+=read;check(downloaded<=size)
                        hash.update(buffer,0,read);output.write(buffer,0,read)
                        val percent=(downloaded*100/size).toInt()
                        if(percent/5!=shown) {shown=percent/5;progress("正在接收音色 $percent%")}
                    }
                } }
                check(downloaded==size && hash.digest().joinToString("") {"%02x".format(it)}==id) {"音色完整性校验失败"}
            } finally {http.disconnect();connection=null}
            var total=0L;var entries=0;val seen=HashSet<String>()
            ZipFile(archive).use {zip->
                val iterator=zip.entries()
                while(iterator.hasMoreElements()) {
                    check(!cancelled())
                    val entry=iterator.nextElement();check(++entries<=8000)
                    val name=entry.name.removeSuffix("/")
                    val path=VoiceLibrary.path(staging,name)
                    check(seen.add(name)) {"重复音色文件"}
                    if(entry.isDirectory) {check(File(path).mkdirs());continue}
                    val file=File(path);file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use {input->file.outputStream().use {output->
                        val buffer=ByteArray(65536)
                        while(true) {
                            check(!cancelled())
                            val read=input.read(buffer);if(read<0)break
                            total+=read;check(total<=805306368L && total<=expanded+32768)
                            output.write(buffer,0,read)
                        }
                    }}
                }
            }
            val descriptor=File(staging,"voicepack.json")
            check(descriptor.length() in 1..32768)
            val manifest=JSONObject(descriptor.readText())
            check(manifest.getInt("version")==1 && manifest.getString("engine") in listOf("kitten","vits","kokoro"))
            for(key in listOf("model","tokens")) check(File(VoiceLibrary.path(staging,manifest.getString(key))).isFile)
            for(key in listOf("voices","lexicon","data_dir")) if(manifest.optString(key).isNotBlank()) check(File(VoiceLibrary.path(staging,manifest.getString(key))).exists())
            check(!cancelled() && !target.exists() && staging.renameTo(target)) {"安装音色失败"}
            progress("音色接收完成，正在准备加载")
        } finally {
            archive.delete()
            if(staging.canonicalPath.startsWith(VoiceLibrary.root(context).canonicalPath+File.separator) && staging.name.startsWith(".staging-")) staging.deleteRecursively()
        }
    }
}
