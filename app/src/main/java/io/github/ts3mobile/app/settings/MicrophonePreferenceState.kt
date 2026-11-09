package io.github.ts3mobile.app.settings

import io.github.ts3mobile.audio.opus.MicrophoneSettings

enum class MicrophonePreferenceField {
    VOLUME,
    NOISE_SUPPRESSION,
    ECHO_CANCELLATION,
    GATE_THRESHOLD,
    ;

    fun applyTo(
        current: MicrophoneSettings,
        requested: MicrophoneSettings,
    ): MicrophoneSettings =
        when (this) {
            VOLUME -> current.copy(volumePercent = requested.volumePercent)
            NOISE_SUPPRESSION -> current.copy(noiseSuppression = requested.noiseSuppression)
            ECHO_CANCELLATION -> current.copy(echoCancellation = requested.echoCancellation)
            GATE_THRESHOLD -> current.copy(gateThresholdDb = requested.gateThresholdDb)
        }
}

enum class MicrophonePreferenceStatus { LOADING, PENDING, SAVING, SAVED, READ_FAILED, WRITE_FAILED }

internal data class MicrophonePreferenceState(
    val settings: MicrophoneSettings = MicrophoneSettings(),
    val status: MicrophonePreferenceStatus = MicrophonePreferenceStatus.LOADING,
)

internal interface MicrophonePreferenceStore {
    suspend fun read(): MicrophoneSettings

    suspend fun write(
        settings: MicrophoneSettings,
        reset: Boolean = false,
    )
}
