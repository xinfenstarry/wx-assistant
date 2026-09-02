package com.xinfen.wxassistant.data

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Stores the user's provider key encrypted with a device-backed Android Keystore key. */
internal class SecureApiKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun read(): String {
        val encoded = preferences.getString(KEY_ENCRYPTED, null) ?: return ""
        return runCatching { decrypt(encoded) }
            .getOrElse {
                preferences.edit().remove(KEY_ENCRYPTED).apply()
                ""
            }
    }

    fun write(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            preferences.edit().remove(KEY_ENCRYPTED).apply()
            return
        }
        preferences.edit()
            .putString(KEY_ENCRYPTED, encrypt(normalized))
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        require(encrypted.size > GCM_IV_BYTES) { "Encrypted API key is invalid" }
        val iv = encrypted.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = encrypted.copyOfRange(GCM_IV_BYTES, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "wx_assistant_deepseek_api_key"
        private const val FILE_NAME = "wx_assistant_secure_preferences"
        private const val KEY_ENCRYPTED = "deepseek_api_key_gcm"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
    }
}
