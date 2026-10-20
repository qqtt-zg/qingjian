//! 一次函数调用；模型参数保持字符串，交给本机工具逐项校验。
package org.qingjian.speech.learning.ai

data class ToolCall(
    val id: String,

    val name: String,

    val arguments: String,
)
