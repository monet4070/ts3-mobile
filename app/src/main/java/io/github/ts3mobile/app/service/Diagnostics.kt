package io.github.ts3mobile.app.service

/**
 * A privacy-preserving view of the current service session.
 *
 * It deliberately contains counters and categories only. Host names, ports,
 * nicknames, channel names, participant names, passwords and protocol payloads
 * never cross this boundary.
 */
data class DiagnosticsSnapshot(
    val uptimeMs: Long = 0L,
    val connectionAttempts: Long = 0L,
    val reconnectAttempts: Long = 0L,
    val successfulConnections: Long = 0L,
    val failedConnections: Long = 0L,
    val retryableFailures: Long = 0L,
    val voiceFramesReceived: Long = 0L,
    val voiceFramesDropped: Long = 0L,
    val microphoneErrors: Long = 0L,
    val channelJoinAttempts: Long = 0L,
    val channelJoinFailures: Long = 0L,
    val lastFailure: DiagnosticFailureKind? = null,
) {
    /** A stable, reviewable export format for sanitized bug reports. */
    fun toRedactedJson(): String =
        buildString {
            append('{')
            append("\"schema\":\"ts3-mobile-diagnostics/v1\"")
            append(",\"uptimeMs\":").append(uptimeMs)
            append(",\"connectionAttempts\":").append(connectionAttempts)
            append(",\"reconnectAttempts\":").append(reconnectAttempts)
            append(",\"successfulConnections\":").append(successfulConnections)
            append(",\"failedConnections\":").append(failedConnections)
            append(",\"retryableFailures\":").append(retryableFailures)
            append(",\"voiceFramesReceived\":").append(voiceFramesReceived)
            append(",\"voiceFramesDropped\":").append(voiceFramesDropped)
            append(",\"microphoneErrors\":").append(microphoneErrors)
            append(",\"channelJoinAttempts\":").append(channelJoinAttempts)
            append(",\"channelJoinFailures\":").append(channelJoinFailures)
            append(",\"lastFailure\":")
            if (lastFailure == null) {
                append("null")
            } else {
                append('"').append(lastFailure.name).append('"')
            }
            append('}')
        }

    companion object {
        val Empty = DiagnosticsSnapshot()
    }
}

enum class DiagnosticFailureKind {
    RETRYABLE_CONNECTION,
    TERMINAL_CONNECTION,
    AUDIO,
    CHANNEL,
}

/** Thread-safe counters for callbacks arriving from protocol and audio threads. */
internal class DiagnosticsRecorder(
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var sessionStartedAtMs = clockMs()
    private var connectionAttempts = 0L
    private var reconnectAttempts = 0L
    private var successfulConnections = 0L
    private var failedConnections = 0L
    private var retryableFailures = 0L
    private var voiceFramesReceived = 0L
    private var voiceFramesDropped = 0L
    private var microphoneErrors = 0L
    private var channelJoinAttempts = 0L
    private var channelJoinFailures = 0L
    private var lastFailure: DiagnosticFailureKind? = null

    fun resetSession() =
        synchronized(lock) {
            sessionStartedAtMs = clockMs()
            connectionAttempts = 0L
            reconnectAttempts = 0L
            successfulConnections = 0L
            failedConnections = 0L
            retryableFailures = 0L
            voiceFramesReceived = 0L
            voiceFramesDropped = 0L
            microphoneErrors = 0L
            channelJoinAttempts = 0L
            channelJoinFailures = 0L
            lastFailure = null
        }

    fun recordConnectionAttempt(reconnecting: Boolean) =
        synchronized(lock) {
            connectionAttempts++
            if (reconnecting) reconnectAttempts++
        }

    fun recordConnectionSuccess() {
        synchronized(lock) { successfulConnections++ }
    }

    fun recordConnectionFailure(retryable: Boolean) =
        synchronized(lock) {
            failedConnections++
            if (retryable) {
                retryableFailures++
                lastFailure = DiagnosticFailureKind.RETRYABLE_CONNECTION
            } else {
                lastFailure = DiagnosticFailureKind.TERMINAL_CONNECTION
            }
        }

    fun recordVoiceFrameReceived() {
        synchronized(lock) { voiceFramesReceived++ }
    }

    fun recordVoiceFrameDropped() {
        synchronized(lock) { voiceFramesDropped++ }
    }

    fun recordMicrophoneError() =
        synchronized(lock) {
            microphoneErrors++
            lastFailure = DiagnosticFailureKind.AUDIO
        }

    fun recordChannelJoinAttempt() {
        synchronized(lock) { channelJoinAttempts++ }
    }

    fun recordChannelJoinFailure() =
        synchronized(lock) {
            channelJoinFailures++
            lastFailure = DiagnosticFailureKind.CHANNEL
        }

    fun snapshot(nowMs: Long = clockMs()): DiagnosticsSnapshot =
        synchronized(lock) {
            DiagnosticsSnapshot(
                uptimeMs = (nowMs - sessionStartedAtMs).coerceAtLeast(0L),
                connectionAttempts = connectionAttempts,
                reconnectAttempts = reconnectAttempts,
                successfulConnections = successfulConnections,
                failedConnections = failedConnections,
                retryableFailures = retryableFailures,
                voiceFramesReceived = voiceFramesReceived,
                voiceFramesDropped = voiceFramesDropped,
                microphoneErrors = microphoneErrors,
                channelJoinAttempts = channelJoinAttempts,
                channelJoinFailures = channelJoinFailures,
                lastFailure = lastFailure,
            )
        }
}
