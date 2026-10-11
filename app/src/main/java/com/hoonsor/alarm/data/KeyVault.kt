package com.hoonsor.alarm.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 的 AES-GCM 金鑰加解密 API key。
 * 金鑰存在硬體安全區，無法匯出；App 解除安裝後金鑰與密文一起消失。
 */
object KeyVault {
    private const val ALIAS = "hoonsor_ai_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val data = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + data, Base64.NO_WRAP)
    }.onFailure { Log.e("KeyVault", "encrypt failed", it) }.getOrNull()

    fun decrypt(cipherText: String?): String? {
        if (cipherText.isNullOrBlank()) return null
        return runCatching {
            val all = Base64.decode(cipherText, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val data = all.copyOfRange(12, all.size)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        }.onFailure { Log.e("KeyVault", "decrypt failed", it) }.getOrNull()
    }
}
