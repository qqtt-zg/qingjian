//! Android Keystore 加密用户自行填写的 API 密钥；密文、地址与模型不参与系统备份。
package org.qingjian.speech.learning.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(context: Context) {
    private val prefs = context.getSharedPreferences("learning_ai", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun load(): Profile {
        val encrypted = prefs.getString("secret", "").orEmpty()
        val key = if (encrypted.isEmpty()) "" else try {
            val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
                String(doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
            }
        } catch (_: Exception) { throw IllegalStateException("保存的密钥无法解密，请重新填写 API Key") }
        return Profile(prefs.getString("address", "").orEmpty(), prefs.getString("model", "").orEmpty(), key)
    }

    fun save(profile: Profile) {
        profile.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val encrypted = cipher.doFinal(profile.key.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("address", profile.address.trim()).putString("model", profile.model.trim())
            .putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "配置保存失败" }
    }

    fun clear() { check(prefs.edit().clear().commit()) { "配置清除失败" } }

    companion object { private const val ALIAS = "qingjian-learning-ai-v1" }
}
