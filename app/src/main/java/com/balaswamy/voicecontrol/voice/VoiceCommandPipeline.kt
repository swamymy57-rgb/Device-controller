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

class VoiceCommandPipeline(
    private val speakerVerifier: SpeakerVerifier,
    private val executeAuthorized: suspend (VerifiedVoiceCommand) -> CommandExecutionResult,
    private val onStateChanged: (AssistantState, String) -> Unit,
) {
    private val executionLock = Mutex()

    suspend fun handleRecognizedCommand(transcript: String, voiceSample: VoiceSample? = null) =
        executionLock.withLock {
            val command = VoiceCommandParser.parse(transcript)
            if (command is VoiceCommand.Unknown) {
                onStateChanged(AssistantState.ERROR, "Unrecognized voice command; not executed.")
                return@withLock
            }

            onStateChanged(AssistantState.VERIFYING, "Verifying Voice")
            val verification = try {
                speakerVerifier.verify(command, voiceSample)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                onStateChanged(
                    AssistantState.ERROR,
                    "Voice verification failed: ${exception.localizedMessage ?: "unknown error"}",
                )
                return@withLock
            }
            when (verification) {
                SpeakerVerificationState.VERIFIED -> {
                    onStateChanged(AssistantState.AUTHORIZED, "Owner verification passed.")
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
                SpeakerVerificationState.NOT_CONFIGURED ->
                    onStateChanged(
                        AssistantState.ERROR,
                        "Speaker verification is not configured; command not executed.",
                    )
                SpeakerVerificationState.REJECTED ->
                    onStateChanged(AssistantState.ERROR, "Voice verification failed; command not executed.")
                SpeakerVerificationState.ERROR ->
                    onStateChanged(
                        AssistantState.ERROR,
                        "Voice verification encountered an error; command not executed.",
                    )
                SpeakerVerificationState.ENROLLING,
                SpeakerVerificationState.READY,
                SpeakerVerificationState.VERIFYING,
                -> onStateChanged(AssistantState.ERROR, "Voice verification did not authorize this command.")
            }
        }
}
