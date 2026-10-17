//! 仅管理应用私有目录中的跟读 WAV，临时录音不进入公共相册或下载目录。
package org.qingjian.speech.learning.oral

import android.content.Context
import java.io.File
import java.util.UUID

object AudioFiles {
    private val pattern = Regex("[a-f0-9-]{36}\\.wav")

    fun temporary(context: Context): File = File(File(context.cacheDir, "oral").apply { mkdirs() }, UUID.randomUUID().toString() + ".wav")

    fun pending(context: Context, name: String): File {
        require(pattern.matches(name))
        return File(context.cacheDir, "oral/$name")
    }

    fun file(context: Context, name: String): File {
        require(pattern.matches(name))
        return File(context.filesDir, "oral/$name")
    }

    fun save(context: Context, source: File): File {
        require(source.canonicalFile.parentFile == File(context.cacheDir, "oral").canonicalFile)
        require(pattern.matches(source.name) && source.isFile && source.length() in 16044..960044)
        val destination = file(context, UUID.randomUUID().toString() + ".wav")
        destination.parentFile!!.mkdirs()
        check(source.renameTo(destination)) { "无法保存录音，请检查存储空间" }
        return destination
    }

    fun clear(context: Context) {
        for(folder in listOf(File(context.filesDir, "oral"), File(context.cacheDir, "oral"))) {
            folder.listFiles()?.filter { it.isFile && pattern.matches(it.name) }?.forEach { it.delete() }
        }
    }
}
