package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.Ts3Participant

data class ParticipantAudioSettings(
    val volumePercent: Int = 100,
    val muted: Boolean = false,
) {
    val gain: Float
        get() = if (muted) 0f else volumePercent.coerceIn(0, 200) / 100f
}

internal fun Ts3Participant.audioControlKey(): String = uniqueIdentifier.ifBlank { "session:$id" }
