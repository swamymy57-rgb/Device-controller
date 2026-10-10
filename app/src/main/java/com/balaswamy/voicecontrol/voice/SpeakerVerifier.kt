package com.balaswamy.voicecontrol.voice

import com.balaswamy.voicecontrol.commands.VoiceCommand

enum class SpeakerVerificationState {
    NOT_CONFIGURED,
    ENROLLING,
    READY,
    VERIFYING,
    VERIFIED,
    REJECTED,
    ERROR,
}

data class VoiceSample(
    val pcm16Audio: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
)

interface SpeakerVerifier {
    suspend fun verify(
        command: VoiceCommand,
        voiceSample: VoiceSample?,
    ): SpeakerVerificationState
}

class UnconfiguredSpeakerVerifier : SpeakerVerifier {
    override suspend fun verify(
        command: VoiceCommand,
        voiceSample: VoiceSample?,
    ): SpeakerVerificationState {
        return SpeakerVerificationState.VERIFIED
    }
}
