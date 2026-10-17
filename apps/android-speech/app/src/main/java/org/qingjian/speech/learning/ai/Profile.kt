//! AI 服务配置；只接受 HTTPS，地址既可填根路径，也可填完整 Chat Completions 端点。
package org.qingjian.speech.learning.ai

import java.net.URI

class Profile(val address: String, val model: String, val key: String) {
    fun endpoint(): String {
        val uri = try { URI(address.trim()) } catch (_: Exception) { throw IllegalArgumentException("API 地址格式无效") }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "请填写不带账号、查询参数或 # 的 HTTPS API 地址" }
        val base = uri.toASCIIString().trimEnd('/')
        require(!base.endsWith("/responses")) { "当前版本支持 Chat Completions，请填写对应地址" }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    fun validate() {
        endpoint()
        require(model.isNotBlank() && model.length <= 200) { "请填写服务商提供的模型 ID" }
        require(key.isNotBlank() && key.length <= 4096 && key.none { it == '\r' || it == '\n' }) { "请填写有效的 API Key" }
    }

    override fun toString(): String = "Profile(model=$model, key=<hidden>)"
}
