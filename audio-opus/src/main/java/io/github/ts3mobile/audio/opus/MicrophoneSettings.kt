package io.github.ts3mobile.audio.opus

/** In-memory capture controls; changing these never starts capture. */
data class MicrophoneSettings(
    val volumePercent: Int = 100,
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val gateThresholdDb: Int = -40,
) {
    init {
        require(volumePercent in 0..200)
        require(gateThresholdDb in -60..-15)
    }
}

enum class EchoCancellationState { PENDING, ENABLED, DISABLED, UNAVAILABLE, FAILED }

data class MicrophoneCaptureState(
    val capturing: Boolean = false,
    val transmitting: Boolean = false,
    val echoCancellation: EchoCancellationState = EchoCancellationState.PENDING,
)

internal data class MicrophoneProcessingOptions(
    val settings: MicrophoneSettings = MicrophoneSettings(),
    val volumeGate: Boolean = false,
) {
    fun sameExceptVolume(other: MicrophoneProcessingOptions?): Boolean =
        other != null && volumeGate == other.volumeGate && settings.copy(volumePercent = other.settings.volumePercent) == other.settings
}
