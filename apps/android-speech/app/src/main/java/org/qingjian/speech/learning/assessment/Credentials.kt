//! 讯飞凭据独立加密保存，沿用手机 Keystore，不与文字 AI 设置混用。
package org.qingjian.speech.learning.assessment

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(context: Context) {
    private val prefs=context.getSharedPreferences("oral_assessment_credentials",0)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): Profile {
        val raw=prefs.getString("secret","").orEmpty()
        if(raw.isEmpty())return Profile("","","")
        try {
            val plain=Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)))
                String(doFinal(Base64.decode(raw,Base64.NO_WRAP)),Charsets.UTF_8)
            }
            val value=JSONObject(plain)
            return Profile(value.getString("app_id"),value.getString("api_key"),value.getString("api_secret"))
        } catch(_: Exception) {throw IllegalStateException("评测凭据无法解密，请重新填写")}
    }
    fun save(profile: Profile) {
        profile.validate()
        val value=JSONObject().put("app_id",profile.appId).put("api_key",profile.apiKey).put("api_secret",profile.apiSecret)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("secret",Base64.encodeToString(encrypted,Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit()) {"无法保存评测配置"}
    }
    fun clear() {check(prefs.edit().clear().commit())}
    companion object {private const val ALIAS="qingjian-oral-assessment-v1"}
}
