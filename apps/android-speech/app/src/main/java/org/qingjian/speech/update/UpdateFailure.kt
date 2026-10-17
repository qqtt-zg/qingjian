//! 面向用户的更新错误，区分协议/完整性问题与可重试网络中断。
package org.qingjian.speech.update

class UpdateFailure(message: String) : Exception(message)
