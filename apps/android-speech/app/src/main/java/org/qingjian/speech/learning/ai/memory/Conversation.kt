//! 一段独立学习会话及其可丢弃的摘要缓存，原始消息仍由学习库保存。
package org.qingjian.speech.learning.ai.memory

data class Conversation(val topic: String, val wordId: Long, val title: String, val summary: String, val through: Long, val revision: Long)
