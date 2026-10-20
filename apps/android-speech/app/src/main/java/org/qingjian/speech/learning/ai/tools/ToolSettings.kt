//! 工具开关独立于密钥保存；不支持函数调用的中转接口可继续纯文字答疑。
package org.qingjian.speech.learning.ai.tools

import android.content.Context

object ToolSettings {
    fun enabled(context: Context) = context.getSharedPreferences("learning_ai_tools", 0).getBoolean("enabled", true)

    fun save(context: Context, value: Boolean) {
        context.getSharedPreferences("learning_ai_tools", 0).edit().putBoolean("enabled", value).apply()
    }
}
