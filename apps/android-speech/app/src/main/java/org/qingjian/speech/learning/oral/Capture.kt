//! 一段短时跟读的结果；音量检测仅提示录音质量，不推断发音准确性。
package org.qingjian.speech.learning.oral

import java.io.File

data class Capture(
    val file: File,

    val duration: Long,

    val quiet: Boolean,
)
