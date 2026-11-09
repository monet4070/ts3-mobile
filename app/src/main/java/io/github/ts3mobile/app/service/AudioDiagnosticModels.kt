package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.AudioRouteKind
import io.github.ts3mobile.audio.opus.AudioRoutingErrorKind
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ConnectionPhase

internal enum class AudioDiagnosticEvent {
    STARTED,
    STOPPED,
    SERVICE_STOPPED,
    PREVIOUS_RUN_INTERRUPTED,
    USER_MARK,
    SELECTED_ROUTE,
    ROUTE_ERROR,
    SAFETY_MUTE,
    ROUTE_SWITCHING,
    FOCUS_GAINED,
    FOCUS_INTERRUPTED,
    ACTUAL_ROUTE,
    OUTPUT_CREATED,
    NETWORK,
}

data class AudioDiagnosticStatus(
    val savedSamples: Int = 0,
    val sessions: Int = 0,
    val lastSampleTimeMs: Long = 0,
    val lastPersistedTimeMs: Long = 0,
    val failedWrites: Long = 0,
    val invalidRecords: Long = 0,
    val marks: Int = 0,
)

internal data class AudioDiagnosticContext(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val playback: PlaybackStatus = PlaybackStatus.STOPPED,
    val selectedRoute: AudioRouteKind = AudioRouteKind.SYSTEM,
    val switching: Boolean = false,
    val safetyMuted: Boolean = false,
    val muted: Boolean = false,
    val routeError: AudioRoutingErrorKind? = null,
    val network: DiagnosticNetwork = DiagnosticNetwork.UNKNOWN,
    val networkGeneration: Long = 0,
    val cpuLockHeld: Boolean = false,
)

internal data class AudioDiagnosticSample(
    val timeMs: Long,
    val elapsedMs: Long,
    val windowMs: Long,
    val session: Long,
    val snapshot: AudioReceiveDiagnostics,
    val context: AudioDiagnosticContext,
)

internal data class AudioDiagnosticEntry(
    val timeMs: Long,
    val session: Long,
    val kind: AudioDiagnosticEvent,
    val value: Long = 0,
)

internal data class AudioDiagnosticSession(val id: Long, val startedMs: Long, val endedMs: Long = 0)

internal data class AudioDiagnosticArchiveState(
    val sessions: List<AudioDiagnosticSession> = emptyList(),
    val samples: List<AudioDiagnosticSample> = emptyList(),
    val events: List<AudioDiagnosticEntry> = emptyList(),
    val failedWrites: Long = 0,
    val invalidRecords: Long = 0,
    val persistedMs: Long = 0,
    val trimmedSamples: Long = 0,
)

internal data class AudioDiagnosticCheckpoint(
    val revision: Long,
    val rows: List<String>,
    val rewrite: Boolean,
    val state: AudioDiagnosticArchiveState,
)
