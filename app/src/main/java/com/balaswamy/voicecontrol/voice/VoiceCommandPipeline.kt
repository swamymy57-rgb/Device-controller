package com.balaswamy.voicecontrol.voice

import com.balaswamy.voicecontrol.commands.VoiceCommand
import com.balaswamy.voicecontrol.commands.VoiceCommandParser
import com.balaswamy.voicecontrol.commands.VerifiedVoiceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AssistantState {
    IDLE,
    LISTENING,
    VERIFYING,
    AUTHORIZED,
    EXECUTING,
    ERROR,
}

data class CommandExecutionResult(
    val succeeded: Boolean,
    val message: String,
)

/**
 * Voice command pipeline without speaker verification.
 * Accepts commands from any speaker and executes authorized commands.
 */
class VoiceCommandPipeline(
    private val executeAuthorized: suspend (VerifiedVoiceCommand) -> CommandExecutionResult,
    private val onStateChanged: (AssistantState, String) -> Unit,
) {
    private val executionLock = Mutex()

    suspend fun handleRecognizedCommand(transcript: String) =
        executionLock.withLock {
            val command = VoiceCommandParser.parse(transcript)
            if (command is VoiceCommand.Unknown) {
                onStateChanged(AssistantState.ERROR, "Unrecognized voice command; not executed.")
                return@withLock
            }

            // No voice verification - accept command from any speaker
            onStateChanged(AssistantState.AUTHORIZED, "Command recognized.")
            onStateChanged(AssistantState.EXECUTING, "Executing")
            try {
                val result = executeAuthorized(VerifiedVoiceCommand.afterSuccessfulVerification(command))
                onStateChanged(
                    if (result.succeeded) AssistantState.IDLE else AssistantState.ERROR,
                    result.message,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                onStateChanged(
                    AssistantState.ERROR,
                    "Command failed: ${exception.localizedMessage ?: "unknown error"}",
                )
            }
        }
}
