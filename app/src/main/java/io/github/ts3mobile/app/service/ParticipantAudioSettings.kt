package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.Ts3Participant
import kotlin.math.pow

private const val PERCEPTUAL_ATTENUATION_EXPONENT = 5f / 3f

data class ParticipantAudioSettings(
    val volumePercent: Int = 100,
    val muted: Boolean = false,
) {
    val gain: Float
        get() {
            if (muted) return 0f
            val ratio = volumePercent.coerceIn(0, 200) / 100f
            return if (ratio < 1f) ratio.pow(PERCEPTUAL_ATTENUATION_EXPONENT) else ratio
        }
}

internal fun Ts3Participant.audioControlKey(): String = uniqueIdentifier.ifBlank { "session:$id" }
