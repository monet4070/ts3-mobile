package io.github.ts3mobile.audio.opus

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import io.github.ts3mobile.protocol.sanitizedFailureTypes

/** Session-owned Android effects. RNNoise and manual gain own NS/AGC policy. */
internal class MicrophoneAudioEffects(audioSessionId: Int) : AutoCloseable {
    private val effects = mutableListOf<AudioEffect>()
    private var echo: AcousticEchoCanceler? = null
    var echoState = EchoCancellationState.PENDING
        private set

    init {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(audioSessionId)?.also(effects::add)
            }
            echoState = if (echo == null) EchoCancellationState.UNAVAILABLE else EchoCancellationState.PENDING
        } catch (error: Throwable) {
            logFailure("create echo cancellation", error)
            echoState = EchoCancellationState.FAILED
        }
        disablePlatformEffect("automatic gain control") {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(audioSessionId) else null
        }
        disablePlatformEffect("noise suppression") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioSessionId) else null
        }
    }

    fun configure(echoCancellation: Boolean) {
        val effect = echo ?: return
        echoState =
            try {
                effectState(echoCancellation, effect.setEnabled(echoCancellation), effect.enabled)
            } catch (error: Throwable) {
                logFailure("configure echo cancellation", error)
                EchoCancellationState.FAILED
            }
    }

    override fun close() {
        effects.forEach { runCatching { it.release() }.onFailure { error -> logFailure("release effect", error) } }
        effects.clear()
    }

    private fun disablePlatformEffect(
        label: String,
        factory: () -> AudioEffect?,
    ) {
        try {
            val effect = factory()?.also(effects::add) ?: return
            if (effect.setEnabled(false) != AudioEffect.SUCCESS || effect.enabled) {
                System.err.println("TS3_AUDIO: platform rejected disabling $label")
            }
        } catch (error: Throwable) {
            logFailure("disable $label", error)
        }
    }

    companion object {
        internal fun effectState(
            requested: Boolean,
            result: Int,
            enabled: Boolean,
        ): EchoCancellationState =
            if (result != AudioEffect.SUCCESS || enabled != requested) {
                EchoCancellationState.FAILED
            } else if (enabled) {
                EchoCancellationState.ENABLED
            } else {
                EchoCancellationState.DISABLED
            }

        private fun logFailure(
            action: String,
            error: Throwable,
        ) {
            System.err.println("TS3_AUDIO: failed to $action: ${error.sanitizedFailureTypes()}")
        }
    }
}
