//! 会话条目，未取得回答的问题保留在本机供手动重试。
package org.qingjian.speech.learning

data class ChatEntry(val id: Long, val role: String, val text: String, val state: String, val replyTo: Long? = null)
