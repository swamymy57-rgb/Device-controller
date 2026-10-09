package com.balaswamy.voicecontrol.data.gemini

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface ValidatedAutomationAction {
    data class Click(
        val target: String? = null,
        val bounds: ScreenBounds? = null,
    ) : ValidatedAutomationAction
    data class Type(val field: String, val text: String) : ValidatedAutomationAction
    data object ScrollUp : ValidatedAutomationAction
    data object ScrollDown : ValidatedAutomationAction
    data class OpenApp(val packageName: String) : ValidatedAutomationAction
    data class ConfirmRequired(val reason: String) : ValidatedAutomationAction
}

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

object AutomationInputSafety {
    private val sensitiveFieldPattern = Regex(
        """\b(password|passcode|pin|one[- ]time(?: password)?|otp|verification code|recovery code|security code|token|secret)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val destructiveActionPattern = Regex(
        """\b(delete|erase|wipe|uninstall|remove|factory reset)\b|\b(empty|clear)\s+(trash|recycle bin|files?|data|storage|repository|repo|account|project|database|drive)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val systemSettingsPattern = Regex(
        """\b(settings|wi-?fi|bluetooth|airplane mode|mobile data|location services|developer options|permission manager|app permissions|system update|unknown sources|factory reset|accessibility settings|change system settings)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitiveField(field: String): Boolean = sensitiveFieldPattern.containsMatchIn(field)

    fun isDestructiveAction(target: String): Boolean = destructiveActionPattern.containsMatchIn(target)

    fun isSystemSettingsAction(target: String): Boolean = systemSettingsPattern.containsMatchIn(target)
}

data class ValidatedAutomationPlan(
    val actions: List<ValidatedAutomationAction>,
)

object AutomationPlanParser {
    private const val MAX_ACTIONS = 10
    private const val MAX_TEXT_LENGTH = 2_000
    private const val MAX_BOUND_COORDINATE = 100_000
    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun parse(response: String): GeminiResult<ValidatedAutomationPlan> {
        if (response.length > MAX_RESPONSE_LENGTH) {
            return invalidPlan("The plan response is too large.")
        }
        return try {
            val json = JSONObject(stripJsonFence(response))
            if (json.keys().asSequence().any { it != "actions" }) {
                return invalidPlan("The plan contains unsupported top-level fields.")
            }
            val actionsJson = json.optJSONArray("actions")
                ?: return invalidPlan("The plan must contain an actions array.")
            if (actionsJson.length() > MAX_ACTIONS) {
                return invalidPlan("The plan contains too many actions.")
            }
            val actions = (0 until actionsJson.length()).map { index ->
                parseAction(actionsJson.getJSONObject(index))
            }
            GeminiResult.Success(ValidatedAutomationPlan(actions))
        } catch (exception: JSONException) {
            invalidPlan("Gemini returned a plan that is not valid JSON.")
        } catch (exception: InvalidAutomationActionException) {
            invalidPlan(exception.message ?: "Gemini returned an unsupported action.")
        }
    }

    private fun parseAction(json: JSONObject): ValidatedAutomationAction {
        val action = requiredText(json, "action", "action")
        val allowedFields = when (action) {
            "CLICK" -> setOf("action", "target", "bounds")
            "TYPE" -> setOf("action", "field", "text")
            "SCROLL_UP", "SCROLL_DOWN" -> setOf("action")
            "OPEN_APP" -> setOf("action", "package", "target")
            "CONFIRM_REQUIRED" -> setOf("action", "reason")
            else -> throw InvalidAutomationActionException("The plan contains an action outside the allowlist.")
        }
        if (json.keys().asSequence().any { it !in allowedFields }) {
            throw InvalidAutomationActionException("The plan contains unsupported fields.")
        }
        return when (action) {
            "CLICK" -> {
                val hasTarget = json.has("target")
                val hasBounds = json.has("bounds")
                if (hasTarget == hasBounds) {
                    throw InvalidAutomationActionException("CLICK must provide either a target or bounds.")
                }
                if (hasTarget) {
                    val target = requiredText(json, "target", "CLICK target")
                    if (AutomationInputSafety.isDestructiveAction(target) ||
                        AutomationInputSafety.isSystemSettingsAction(target)
                    ) {
                        throw InvalidAutomationActionException(
                            "System settings changes and destructive actions are not allowed.",
                        )
                    }
                    ValidatedAutomationAction.Click(
                        target = target,
                    )
                } else {
                    ValidatedAutomationAction.Click(bounds = parseBounds(json.getJSONObject("bounds")))
                }
            }
            "TYPE" -> {
                val field = requiredText(json, "field", "TYPE field")
                if (AutomationInputSafety.isSensitiveField(field)) {
                    throw InvalidAutomationActionException(
                        "Typing into password or verification fields is not allowed.",
                    )
                }
                ValidatedAutomationAction.Type(field, requiredText(json, "text", "TYPE text"))
            }
            "SCROLL_UP" -> ValidatedAutomationAction.ScrollUp
            "SCROLL_DOWN" -> ValidatedAutomationAction.ScrollDown
            "OPEN_APP" -> {
                if (json.has("package") == json.has("target")) {
                    throw InvalidAutomationActionException("OPEN_APP must provide either a package or target.")
                }
                val appName = requiredText(
                    json,
                    if (json.has("package")) "package" else "target",
                    "OPEN_APP target",
                )
                if (json.has("package") && !packageNamePattern.matches(appName)) {
                    throw InvalidAutomationActionException("OPEN_APP must name a valid package.")
                }
                if (AutomationInputSafety.isSystemSettingsAction(appName)) {
                    throw InvalidAutomationActionException("Automating Android system settings is not allowed.")
                }
                ValidatedAutomationAction.OpenApp(appName)
            }
            "CONFIRM_REQUIRED" -> ValidatedAutomationAction.ConfirmRequired(
                requiredText(json, "reason", "confirmation reason"),
            )
            else -> throw InvalidAutomationActionException("The plan contains an action outside the allowlist.")
        }
    }

    private fun requiredText(json: JSONObject, key: String, label: String): String {
        if (!json.has(key) || json.isNull(key) || json.get(key) !is String) {
            throw InvalidAutomationActionException("The plan has an invalid $label.")
        }
        val value = json.getString(key).trim()
        if (value.isEmpty() || value.length > MAX_TEXT_LENGTH || value.any(Char::isISOControl)) {
            throw InvalidAutomationActionException("The plan has an invalid $label.")
        }
        return value
    }

    private fun parseBounds(json: JSONObject): ScreenBounds {
        val fields = setOf("left", "top", "right", "bottom")
        if (json.keys().asSequence().any { it !in fields } ||
            fields.any { !json.has(it) || json.isNull(it) || json.get(it) !is Number }
        ) {
            throw InvalidAutomationActionException("CLICK bounds must contain left, top, right, and bottom.")
        }
        val coordinates = fields.associateWith { field ->
            val value = json.getDouble(field)
            if (value % 1.0 != 0.0 || value < 0 || value > MAX_BOUND_COORDINATE) {
                throw InvalidAutomationActionException("CLICK bounds contain an invalid coordinate.")
            }
            value.toInt()
        }
        val bounds = ScreenBounds(
            left = coordinates.getValue("left"),
            top = coordinates.getValue("top"),
            right = coordinates.getValue("right"),
            bottom = coordinates.getValue("bottom"),
        )
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            throw InvalidAutomationActionException("CLICK bounds must have a positive width and height.")
        }
        return bounds
    }

    private fun stripJsonFence(response: String): String {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```JSON")
            .removePrefix("```").removeSuffix("```").trim()
    }

    private fun invalidPlan(message: String) =
        GeminiResult.Error(GeminiErrorState.INVALID_RESPONSE, message)

    private const val MAX_RESPONSE_LENGTH = 32_000

    private class InvalidAutomationActionException(message: String) : Exception(message)
}

object GeneratedCodeParser {
    fun parse(response: String): GeminiResult<GeneratedCode> {
        return try {
            val json = JSONObject(stripJsonFence(response))
            val language = json.optString("language").trim()
            val code = json.optString("code").trim()
            if (language.isEmpty() || language == "null" || code.isEmpty() || code == "null") {
                parseMarkdownCode(response)
            } else {
                GeminiResult.Success(
                    GeneratedCode(
                        language = language,
                        code = code,
                        explanation = json.optString("explanation")
                            .takeUnless { it.isBlank() || it == "null" },
                    ),
                )
            }
        } catch (exception: JSONException) {
            parseMarkdownCode(response)
        }
    }

    private fun parseMarkdownCode(response: String): GeminiResult<GeneratedCode> {
        val match = Regex("(?s)```([A-Za-z0-9_+#.-]*)\\s*\\n(.*?)```").find(response)
            ?: return GeminiResult.Error(
                GeminiErrorState.INVALID_RESPONSE,
                "Gemini did not return code in the expected format.",
            )
        val code = match.groupValues[2].trim()
        if (code.isBlank()) {
            return GeminiResult.Error(GeminiErrorState.INVALID_RESPONSE, "Gemini returned empty code.")
        }
        return GeminiResult.Success(
            GeneratedCode(
                language = match.groupValues[1].ifBlank { "text" },
                code = code,
                explanation = response.substring(0, match.range.first).trim().ifBlank { null },
            ),
        )
    }

    private fun stripJsonFence(response: String): String {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```JSON")
            .removePrefix("```").removeSuffix("```").trim()
    }
}
