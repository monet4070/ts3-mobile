package io.github.ts3mobile.audio.opus

/** Latest scalar only; no samples or history. All revisions belong to one capture. */
data class MicrophoneLevel(
    val captureGeneration: Long,
    val processingRevision: Long,
    val routeRevision: Long,
    val measuredAtNanos: Long,
    val rmsDb: Double,
    val gateEnabled: Boolean,
    val gateOpen: Boolean,
    val transmitting: Boolean,
    val thresholdDb: Int,
)

/** Observation does not start capture. Callers must close when their UI stops. */
interface MicrophoneLevelSubscription : AutoCloseable {
    val latest: MicrophoneLevel?
}
