package com.balaswamy.voicecontrol.data.secure

interface GeminiApiKeyStorage {
    fun saveGeminiApiKey(apiKey: String)
    fun getGeminiApiKey(): String?
    fun hasGeminiApiKey(): Boolean
    fun clearGeminiApiKey()
}

class GeminiApiKeyRepository(
    private val storage: GeminiApiKeyStorage,
) {
    fun save(apiKey: String) {
        require(apiKey.isNotBlank()) { "API key must not be blank." }
        storage.saveGeminiApiKey(apiKey.trim())
    }

    fun getForRequest(): String? = storage.getGeminiApiKey()?.takeIf(String::isNotBlank)

    fun isConfigured(): Boolean = getForRequest() != null

    fun clear() = storage.clearGeminiApiKey()
}
