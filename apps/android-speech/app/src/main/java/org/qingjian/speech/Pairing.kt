//! 校验用户主动粘贴的 HTTPS 配对地址，凭证仅发给原始主机。
package org.qingjian.speech

import java.net.URI

data class Pairing(val base: String, val token: String) {
    companion object {
        fun parse(value: String): Pairing {
            val uri = URI(value.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.path in listOf("", "/")) { "请粘贴完整 HTTPS 手机连接地址" }
            val token = uri.fragment ?: ""
            require(token.matches(Regex("[a-fA-F0-9]{32}"))) { "链接缺少配对部分，请完整复制 # 后的内容" }
            return Pairing("https://${uri.rawAuthority}", token)
        }
    }
}
