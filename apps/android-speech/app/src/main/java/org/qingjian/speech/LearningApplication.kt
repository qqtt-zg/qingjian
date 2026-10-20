//! 在所有请求入口统一记录用量，不保存密钥、请求正文或隐藏推理。
package org.qingjian.speech

import android.app.Application
import org.qingjian.speech.learning.archive.Usage

class LearningApplication:Application() {
    override fun onCreate(){super.onCreate();Usage.recorder={task,data->Usage(this).record(task,data)}}
}
