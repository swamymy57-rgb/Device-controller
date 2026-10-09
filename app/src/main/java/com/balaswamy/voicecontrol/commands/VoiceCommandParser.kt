package com.balaswamy.voicecontrol.commands

sealed interface VoiceCommand {
    data class Click(val target: String) : VoiceCommand
    data class Type(val text: String, val field: String? = null) : VoiceCommand
    data class Scroll(val direction: Direction) : VoiceCommand {
        enum class Direction { UP, DOWN }
    }
    data class OpenApp(val name: String) : VoiceCommand
    data class GenerateCode(val prompt: String) : VoiceCommand
    data class AskAI(val prompt: String) : VoiceCommand
    data object Confirm : VoiceCommand
    data object Cancel : VoiceCommand
    data class Unknown(val original: String) : VoiceCommand
}

class VerifiedVoiceCommand private constructor(val command: VoiceCommand) {
    companion object {
        internal fun afterSuccessfulVerification(command: VoiceCommand) =
            VerifiedVoiceCommand(command)
    }
}

object VoiceCommandParser {
    fun parse(input: String): VoiceCommand {
        val command = input.trim().trimEnd('.', '!', '?')
        if (command.isEmpty()) return VoiceCommand.Unknown(input)

        when (command.lowercase()) {
            "scroll up" -> return VoiceCommand.Scroll(VoiceCommand.Scroll.Direction.UP)
            "scroll down" -> return VoiceCommand.Scroll(VoiceCommand.Scroll.Direction.DOWN)
            "confirm" -> return VoiceCommand.Confirm
            "cancel" -> return VoiceCommand.Cancel
        }

        match(command, Regex("^click\\s+(.+)$", RegexOption.IGNORE_CASE))?.let {
            return VoiceCommand.Click(it.groupValues[1].trim())
        }
        match(command, Regex("^type\\s+(.+?)\\s+in\\s+(.+)$", RegexOption.IGNORE_CASE))?.let {
            return VoiceCommand.Type(it.groupValues[1].trim(), it.groupValues[2].trim())
        }
        match(command, Regex("^type\\s+(.+)$", RegexOption.IGNORE_CASE))?.let {
            return VoiceCommand.Type(it.groupValues[1].trim())
        }
        match(command, Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE))?.let {
            return VoiceCommand.OpenApp(it.groupValues[1].trim())
        }
        match(command, Regex("^generate\\s+code\\s+for\\s+(.+)$", RegexOption.IGNORE_CASE))?.let {
            return VoiceCommand.GenerateCode(it.groupValues[1].trim())
        }
        match(command, Regex("^(?:ask ai|ask)\\s+(.+)$", RegexOption.IGNORE_CASE))?.let {
            return VoiceCommand.AskAI(it.groupValues[1].trim())
        }
        return VoiceCommand.Unknown(input)
    }

    private fun match(input: String, regex: Regex) = regex.matchEntire(input)
}
