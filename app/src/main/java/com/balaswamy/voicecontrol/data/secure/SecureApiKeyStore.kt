package com.balaswamy.voicecontrol.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureApiKeyStore(context: Context) : GeminiApiKeyStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun saveGeminiApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key must not be blank." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encryptedBytes = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val storedValue = cipher.iv + encryptedBytes
        check(preferences.edit().putString(KEY_ENCRYPTED_VALUE, Base64.encodeToString(storedValue, Base64.NO_WRAP)).commit()) {
            "Unable to persist the encrypted API key."
        }
    }

    override fun getGeminiApiKey(): String? {
        val encodedValue = preferences.getString(KEY_ENCRYPTED_VALUE, null) ?: return null
        val storedValue = Base64.decode(encodedValue, Base64.NO_WRAP)
        require(storedValue.size > GCM_IV_LENGTH_BYTES) { "Stored API key data is invalid." }

        val iv = storedValue.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val encryptedBytes = storedValue.copyOfRange(GCM_IV_LENGTH_BYTES, storedValue.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encryptedBytes).toString(Charsets.UTF_8)
    }

    override fun hasGeminiApiKey(): Boolean = preferences.contains(KEY_ENCRYPTED_VALUE)

    override fun clearGeminiApiKey() {
        check(preferences.edit().remove(KEY_ENCRYPTED_VALUE).commit()) {
            "Unable to clear the encrypted API key."
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "voicecontrol.gemini.api-key.v1"
        const val KEY_ENCRYPTED_VALUE = "gemini_api_key_encrypted"
        const val PREFERENCES_NAME = "secure_api_key_storage"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
