package com.balaswamy.voicecontrol.voice

/**
 * Speaker verification interface. Voice verification (speaker identification) is not configured.
 * Commands are accepted from any speaker without voice identity checks.
 * Speech recognition transcribes speech; it is not speaker authentication.
 */
interface SpeakerVerifier {
    suspend fun verify(command: String): Boolean
}

/**
 * Always-allow speaker verifier - accepts commands from any speaker.
 * No voice biometrics, enrollment, or speaker matching is performed.
 */
class AlwaysAllowSpeakerVerifier : SpeakerVerifier {
    override suspend fun verify(command: String): Boolean = true
}
