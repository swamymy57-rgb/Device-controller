package com.balaswamy.voicecontrol.commands

fun VoiceCommand.safeDisplayText(): String = when (this) {
    is VoiceCommand.Click -> "Click ${sanitizeLabel(target)}"
    is VoiceCommand.Type -> field
        ?.takeIf(String::isNotBlank)
        ?.let { "Type into ${sanitizeLabel(it)}" }
        ?: "Type into the active field"
    is VoiceCommand.Scroll -> "Scroll ${direction.name.lowercase()}"
    is VoiceCommand.OpenApp -> "Open ${sanitizeLabel(name)}"
    is VoiceCommand.GenerateCode -> "Generate code"
    is VoiceCommand.AskAI -> "Ask assistant"
    VoiceCommand.Confirm -> "Confirm pending action"
    VoiceCommand.Cancel -> "Cancel pending action"
    is VoiceCommand.Unknown -> "Unrecognized voice command"
}

fun VoiceCommand.safeActionName(): String = when (this) {
    is VoiceCommand.Click -> "Click"
    is VoiceCommand.Type -> "Type"
    is VoiceCommand.Scroll -> "Scroll"
    is VoiceCommand.OpenApp -> "Open app"
    is VoiceCommand.GenerateCode -> "Generate code"
    is VoiceCommand.AskAI -> "Ask assistant"
    VoiceCommand.Confirm -> "Confirm"
    VoiceCommand.Cancel -> "Cancel"
    is VoiceCommand.Unknown -> "No action"
}

private fun sanitizeLabel(value: String): String {
    val sanitized = value
        .replace(
            Regex(
                """(?i)\b(password|passcode|pin|otp|token|secret|api[_ -]?key|authorization|bearer)\b\s*[:=]?\s*\S+""",
            ),
            "[redacted]",
        )
        .replace(Regex("""AIza[0-9A-Za-z_-]{20,}"""), "[redacted]")
        .replace(
            Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"""),
            "[redacted]",
        )
        .filterNot(Char::isISOControl)
        .take(120)
    return sanitized.ifBlank { "[redacted]" }
}
