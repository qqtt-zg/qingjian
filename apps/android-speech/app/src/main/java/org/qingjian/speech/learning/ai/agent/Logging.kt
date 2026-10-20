//! 禁用代理库正文日志；Android 原生日志后端不受 SLF4J 的 NOP 配置控制。
package org.qingjian.speech.learning.ai.agent

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level

object Logging {
    fun disable() {
        KotlinLoggingConfiguration.logStartupMessage = false
        KotlinLoggingConfiguration.direct.logLevel = Level.OFF
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
    }
}
