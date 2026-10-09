package com.balaswamy.voicecontrol.data.gemini

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiApiService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    @Throws(IOException::class, GeminiApiException::class)
    fun generateContent(apiKey: String, request: GeminiRequest): String {
        require(apiKey.isNotBlank()) { "An API key is required to make a Gemini request." }
        require(request.prompt.isNotBlank()) { "A prompt is required to make a Gemini request." }

        val url = "$API_BASE_URL/$MODEL:generateContent"
        val requestJson = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", request.prompt)),
                    ),
                ),
            )
        request.responseMimeType?.let { mimeType ->
            requestJson.put("generationConfig", JSONObject().put("responseMimeType", mimeType))
        }

        val httpRequest = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GeminiApiException(
                    response.code,
                    "Gemini request failed with HTTP ${response.code}. Check the key and try again.",
                )
            }
            return try {
                extractText(JSONObject(responseBody))
            } catch (exception: JSONException) {
                throw GeminiApiException(null, "Gemini returned an invalid response.")
            }
        }
    }

    private fun extractText(response: JSONObject): String {
        val candidates = response.optJSONArray("candidates")
            ?: throw JSONException("Missing candidates.")
        val text = buildString {
            for (candidateIndex in 0 until candidates.length()) {
                val parts = candidates.optJSONObject(candidateIndex)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: continue
                for (partIndex in 0 until parts.length()) {
                    parts.optJSONObject(partIndex)?.optString("text")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::append)
                }
            }
        }.trim()
        if (text.isBlank()) {
            val blockReason = response.optJSONObject("promptFeedback")?.optString("blockReason")
            if (!blockReason.isNullOrBlank() && blockReason != "null") {
                throw GeminiApiException(null, "Gemini blocked the prompt ($blockReason).")
            }
            throw JSONException("No text candidates.")
        }
        return text
    }

    private companion object {
        const val API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-2.5-flash"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class GeminiApiException(
    val httpStatus: Int?,
    override val message: String,
) : Exception(message)
