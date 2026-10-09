package com.balaswamy.voicecontrol.data.gemini

import com.balaswamy.voicecontrol.data.secure.GeminiApiKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.security.GeneralSecurityException

class GeminiRepository(
    private val apiKeyRepository: GeminiApiKeyRepository,
    private val apiService: GeminiApiService = GeminiApiService(),
) {
    suspend fun askQuestion(question: String): GeminiResult<String> =
        generateText(question)

    suspend fun generateText(prompt: String): GeminiResult<String> {
        if (prompt.isBlank()) {
            return GeminiResult.Error(GeminiErrorState.INVALID_RESPONSE, "Enter a prompt first.")
        }
        return request(
            GeminiRequest(
                prompt = """
                    Answer the following request clearly and helpfully. Do not claim to perform actions.

                    Request:
                    $prompt
                """.trimIndent(),
            ),
        )
    }

    suspend fun planAutomation(prompt: String): GeminiResult<ValidatedAutomationPlan> {
        if (prompt.isBlank()) {
            return GeminiResult.Error(GeminiErrorState.INVALID_RESPONSE, "Enter an automation request first.")
        }
        val result = request(
            GeminiRequest(
                prompt = """
                    Convert this user's request into a JSON automation plan. Return JSON only, using
                    {"actions":[...]} with actions from this allowlist only:
                    {"action":"CLICK","target":"visible UI text"}
                    {"action":"TYPE","field":"visible field label","text":"text to enter"}
                    {"action":"SCROLL_UP"}
                    {"action":"SCROLL_DOWN"}
                    {"action":"OPEN_APP","target":"installed app name"}
                    {"action":"CLICK","bounds":{"left":10,"top":10,"right":100,"bottom":60}}
                    {"action":"CONFIRM_REQUIRED","reason":"what the user must review"}
                    Return bounds instead of target only when visible text is unavailable. Never
                    request or enter passwords, passcodes, verification codes, or tokens. Never
                    include shell commands, arbitrary intents, destructive actions, file/data
                    deletion, or actions not listed above. Mark important actions with
                    CONFIRM_REQUIRED. If no safe plan is possible, return
                    {"actions":[]}.

                    User request:
                    $prompt
                """.trimIndent(),
                responseMimeType = "application/json",
            ),
        )
        return when (result) {
            is GeminiResult.Error -> result
            is GeminiResult.Success -> AutomationPlanParser.parse(result.value)
        }
    }

    suspend fun generateCode(prompt: String): GeminiResult<GeneratedCode> {
        if (prompt.isBlank()) {
            return GeminiResult.Error(GeminiErrorState.INVALID_RESPONSE, "Enter a code-generation prompt first.")
        }
        val result = request(
            GeminiRequest(
                prompt = """
                    Generate code for this request:
                    $prompt

                    Return a JSON object with a "language" string, a "code" string containing only
                    source code, and an optional "explanation" string. Do not include executable
                    automation instructions. Escape JSON strings correctly.
                """.trimIndent(),
                responseMimeType = "application/json",
            ),
        )
        return when (result) {
            is GeminiResult.Error -> result
            is GeminiResult.Success -> GeneratedCodeParser.parse(result.value)
        }
    }

    private suspend fun request(request: GeminiRequest): GeminiResult<String> =
        withContext(Dispatchers.IO) {
            val apiKey = try {
                apiKeyRepository.getForRequest()
            } catch (exception: GeneralSecurityException) {
                return@withContext GeminiResult.Error(
                    GeminiErrorState.KEY_STORAGE_ERROR,
                    "Could not decrypt the saved Gemini API key. Please save it again in Settings.",
                )
            } catch (exception: IllegalArgumentException) {
                return@withContext GeminiResult.Error(
                    GeminiErrorState.KEY_STORAGE_ERROR,
                    "The saved Gemini API key is invalid. Please save it again in Settings.",
                )
            } catch (exception: IllegalStateException) {
                return@withContext GeminiResult.Error(
                    GeminiErrorState.KEY_STORAGE_ERROR,
                    "Could not read the saved Gemini API key.",
                )
            } catch (exception: SecurityException) {
                return@withContext GeminiResult.Error(
                    GeminiErrorState.KEY_STORAGE_ERROR,
                    "Android could not access the saved Gemini API key.",
                )
            } ?: return@withContext GeminiResult.Error(
                GeminiErrorState.GEMINI_API_KEY_NOT_CONFIGURED,
                "Please add your Gemini API key in Settings.",
            )

            try {
                GeminiResult.Success(apiService.generateContent(apiKey, request))
            } catch (exception: GeminiApiException) {
                GeminiResult.Error(
                    if (exception.httpStatus == null) GeminiErrorState.INVALID_RESPONSE
                    else GeminiErrorState.REQUEST_FAILED,
                    exception.message,
                )
            } catch (exception: IOException) {
                GeminiResult.Error(GeminiErrorState.NETWORK_ERROR, "Could not connect to Gemini. Check your network.")
            } catch (exception: IllegalArgumentException) {
                GeminiResult.Error(
                    GeminiErrorState.REQUEST_FAILED,
                    "The saved Gemini API key or request is invalid. Check Settings and try again.",
                )
            } catch (exception: JSONException) {
                GeminiResult.Error(GeminiErrorState.INVALID_RESPONSE, "Gemini returned an invalid response.")
            }
        }
}

class CodeGenerationRepository(
    private val geminiRepository: GeminiRepository,
) {
    suspend fun generateCode(prompt: String): GeminiResult<GeneratedCode> =
        geminiRepository.generateCode(prompt)
}
