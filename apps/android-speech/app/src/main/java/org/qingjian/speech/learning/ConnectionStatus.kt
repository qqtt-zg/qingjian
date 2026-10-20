//! 连接摘要的统一分类；不把音色播放状态当作网络连接状态。
package org.qingjian.speech.learning

import org.qingjian.speech.R

enum class ConnectionStatus(val label: String, val color: Int, val symbol: String) {
    CONNECTED("已连接", R.color.system_green, "●"),
    CONNECTING("连接中", R.color.system_amber, "◌"),
    EXPIRED("配对失效", R.color.system_red, "●"),
    OFFLINE("未连接", R.color.text_secondary, "○");

    companion object {
        fun from(message: String): ConnectionStatus = when {
            message.contains("失效") || message.contains("无效") -> EXPIRED
            message.contains("正在连接") || message.contains("重新连接") -> CONNECTING
            message.contains("已停止") || message.contains("尚未连接") || message.contains("未连接") -> OFFLINE
            message.contains("已连接") || message.contains("连接保留") || message.contains("电脑已暂停") -> CONNECTED
            else -> OFFLINE
        }
    }
}
