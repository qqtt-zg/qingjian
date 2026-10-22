//! 释义表的落盘位置与首次释放：随包 assets 打底，下载通道覆盖更新。
//!
//! 两层来源，优先级从低到高：
//! 1. `assets/glossaries/`——随 APK 打包的基线表，装完就能离线查词，不需要联网。
//! 2. 应用私有目录——下载通道更新后的版本，跟 `data-vN` 走。
//!
//! `Glossary.open` 要 `File`（mmap），assets 是流，所以首次使用时把 assets 里的表
//! 复制到私有目录再打开。复制只在私有目录没有该表时做，下载覆盖后不会被旧版本冲掉。
package org.qingjian.speech.learning.glossary

import android.content.Context
import java.io.File

object GlossaryFiles {
    const val DIRECTORY = "glossaries"

    /** 下载与更新后的表放这里；与音色包分开，避免误删。 */
    fun directory(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * 某语言可用的表，优先私有目录（下载覆盖版），其次随包 assets（基线）。
     *
     * 返回 null 表示这个语言没有可用的表——调用方据此说「查不到」，不要猜。
     */
    fun installed(context: Context, language: String): File? {
        val name = GlossaryNames.assetName(language) ?: return null
        val updated = File(directory(context), name)
        if (updated.isFile) return updated
        return releaseFromAssets(context, name)
    }

    /**
     * 把随包的表释放到私有目录，返回释放后的文件；这个语言没有随包表时返回 null。
     *
     * 先写 `.release` 临时文件再改名：中途被杀不会留下半截表，否则 mmap 会读到坏文件。
     */
    private fun releaseFromAssets(context: Context, name: String): File? {
        val target = File(directory(context), name)
        if (target.isFile) return target
        val temporary = File(directory(context), "$name.release")
        return try {
            context.assets.open("$DIRECTORY/$name").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() > 0) { "随包释义表为空" }
            // 先删掉可能存在的旧目标再改名：同一目录内 renameTo 是原子的，
            // 中途被杀最多留一个临时文件，不会让 mmap 读到半截表。
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "随包释义表释放失败" }
            target.takeIf { it.isFile }
        } catch (error: Exception) {
            temporary.delete()
            null
        }
    }
}
