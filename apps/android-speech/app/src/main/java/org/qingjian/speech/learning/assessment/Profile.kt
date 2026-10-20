//! 讯飞评测凭据只用于固定服务，不通过日志或模型上下文传播。
package org.qingjian.speech.learning.assessment

class Profile(val appId: String, val apiKey: String, val apiSecret: String) {
    fun validate() {
        require(appId.matches(Regex("[A-Za-z0-9]{6,32}"))) {"请填写有效的讯飞 AppID"}
        require(apiKey.matches(Regex("[A-Za-z0-9]{16,128}"))) {"请填写有效的讯飞 APIKey"}
        require(apiSecret.length in 16..256 && apiSecret.all {it.code in 33..126}) {"请填写有效的讯飞 APISecret"}
    }
    fun same(other: Profile)=appId==other.appId && apiKey==other.apiKey && apiSecret==other.apiSecret
    override fun toString()="AssessmentProfile(credentials=<hidden>)"
}
