package io.github.ts3mobile.audio.opus

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import io.github.ts3mobile.protocol.sanitizedFailureTypes

/**
 * Builds the platform audio-effect chain for a capture session: the acoustic
 * echo canceler enabled, platform automatic gain control disabled (RNNoise
 * owns the signal path). Unavailable or unconfigurable effects are skipped
 * with a sanitized log line, never fatal. Extracted from
 * OpusMicrophoneCapture per the architecture decomposition plan.
 */
internal object MicrophoneAudioEffects {
    fun create(audioSessionId: Int): List<AudioEffect> =
        buildList {
            if (AcousticEchoCanceler.isAvailable()) {
                createConfiguredEffect("acoustic echo canceler", enabled = true) {
                    AcousticEchoCanceler.create(audioSessionId)
                }?.let(::add)
            }
            if (AutomaticGainControl.isAvailable()) {
                createConfiguredEffect("automatic gain control", enabled = false) {
                    AutomaticGainControl.create(audioSessionId)
                }?.let(::add)
            }
        }

    fun release(effects: List<AudioEffect>) {
        effects.forEach { runCatching { it.release() } }
    }

    private fun <T : AudioEffect> createConfiguredEffect(
        label: String,
        enabled: Boolean,
        factory: () -> T?,
    ): T? {
        val effect =
            try {
                factory()
            } catch (error: Throwable) {
                System.err.println(
                    "TS3_AUDIO: $label unavailable: ${error.sanitizedFailureTypes()}",
                )
                return null
            } ?: return null

        return try {
            effect.enabled = enabled
            System.err.println("TS3_AUDIO: ${if (enabled) "enabled" else "disabled"} $label")
            effect
        } catch (error: Throwable) {
            runCatching { effect.release() }
            System.err.println(
                "TS3_AUDIO: failed to configure $label: ${error.sanitizedFailureTypes()}",
            )
            null
        }
    }
}
