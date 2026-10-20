//! 固定讯飞 WSS 地址的 RFC1123/HMAC-SHA256 鉴权；签名地址不得记录。
package org.qingjian.speech.learning.assessment

import android.util.Base64
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Signature {
    const val HOST="ise-api.xfyun.cn"
    const val PATH="/v2/open-ise"
    fun path(profile: Profile, now: Date=Date()): String {
        profile.validate()
        val date=SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US).apply {timeZone=TimeZone.getTimeZone("GMT")}.format(now)
        val source="host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac=Mac.getInstance("HmacSHA256").apply {init(SecretKeySpec(profile.apiSecret.toByteArray(Charsets.UTF_8),"HmacSHA256"))}
        val signed=Base64.encodeToString(mac.doFinal(source.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        val auth="api_key=\"${profile.apiKey}\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signed\""
        fun encode(value: String)=URLEncoder.encode(value,"UTF-8")
        return "$PATH?authorization=${encode(Base64.encodeToString(auth.toByteArray(Charsets.UTF_8),Base64.NO_WRAP))}&date=${encode(date)}&host=$HOST"
    }
}
