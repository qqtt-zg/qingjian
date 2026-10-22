//! 释义表在应用私有目录里的位置。与音色包分开放 `glossaries/`：
//! 音色包按 64 位哈希分目录，这里是固定文件名，混在一起容易误删。
package org.qingjian.speech.learning.glossary

import android.content.Context
import java.io.File

object GlossaryFiles {
    const val DIRECTORY = "glossaries"

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /** 某语言已装好的表；没装过返回 null。 */
    fun installed(context: Context, language: String): File? {
        val name = GlossaryNames.assetName(language) ?: return null
        return File(directory(context), name).takeIf { it.isFile }
    }
}
