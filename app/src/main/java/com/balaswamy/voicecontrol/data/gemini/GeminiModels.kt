package com.balaswamy.voicecontrol.data.gemini

data class GeminiRequest(
    val prompt: String,
    val responseMimeType: String? = null,
)

data class GeneratedCode(
    val language: String,
    val code: String,
    val explanation: String? = null,
)

enum class GeminiErrorState {
    GEMINI_API_KEY_NOT_CONFIGURED,
    KEY_STORAGE_ERROR,
    NETWORK_ERROR,
    REQUEST_FAILED,
    INVALID_RESPONSE,
}

sealed interface GeminiResult<out T> {
    data class Success<T>(val value: T) : GeminiResult<T>
    data class Error(
        val state: GeminiErrorState,
        val message: String,
    ) : GeminiResult<Nothing>
}
