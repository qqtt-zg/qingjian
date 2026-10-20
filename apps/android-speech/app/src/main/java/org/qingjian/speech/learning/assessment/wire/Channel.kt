//! 固定评测连接的可替换边界；测试使用内存通道，正式连接由 TLS 传输提供。
package org.qingjian.speech.learning.assessment.wire

interface Channel : AutoCloseable {
    fun open()
    fun send(text: String)
    fun receive(): String
}
