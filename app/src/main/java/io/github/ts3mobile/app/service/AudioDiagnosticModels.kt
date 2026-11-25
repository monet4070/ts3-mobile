package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioDiagnosticEventKind
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import io.github.ts3mobile.audio.opus.AudioRouteKind
import io.github.ts3mobile.audio.opus.AudioRoutingErrorKind
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.VoiceTimingWindow

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
    OUTPUT_PERFORMANCE_MODE,
    OUTPUT_RETIRED,
    OUTPUT_CREATE_SLOW_MS,
    OUTPUT_START_SLOW_MS,
    RECEIVE_DELAY_PROFILE,
    ACTUAL_OUTPUT_DEVICE,
    ACTUAL_INPUT_DEVICE,
    SYSTEM_MICROPHONE_MUTE,
    FIRST_WRITE_TO_HEAD_US,
}

/**
 * Exhaustive on purpose: a new audio kind becomes an app compile error instead of a
 * runtime `valueOf` failure on the audio worker or a system focus/routing callback.
 */
internal fun audioDiagnosticEvent(kind: AudioDiagnosticEventKind): AudioDiagnosticEvent =
    when (kind) {
        AudioDiagnosticEventKind.FOCUS_GAINED -> AudioDiagnosticEvent.FOCUS_GAINED
        AudioDiagnosticEventKind.FOCUS_INTERRUPTED -> AudioDiagnosticEvent.FOCUS_INTERRUPTED
        AudioDiagnosticEventKind.ACTUAL_ROUTE -> AudioDiagnosticEvent.ACTUAL_ROUTE
        AudioDiagnosticEventKind.OUTPUT_CREATED -> AudioDiagnosticEvent.OUTPUT_CREATED
        AudioDiagnosticEventKind.OUTPUT_PERFORMANCE_MODE -> AudioDiagnosticEvent.OUTPUT_PERFORMANCE_MODE
        AudioDiagnosticEventKind.OUTPUT_RETIRED -> AudioDiagnosticEvent.OUTPUT_RETIRED
        AudioDiagnosticEventKind.OUTPUT_CREATE_SLOW_MS -> AudioDiagnosticEvent.OUTPUT_CREATE_SLOW_MS
        AudioDiagnosticEventKind.OUTPUT_START_SLOW_MS -> AudioDiagnosticEvent.OUTPUT_START_SLOW_MS
        AudioDiagnosticEventKind.RECEIVE_DELAY_PROFILE -> AudioDiagnosticEvent.RECEIVE_DELAY_PROFILE
        AudioDiagnosticEventKind.ACTUAL_OUTPUT_DEVICE -> AudioDiagnosticEvent.ACTUAL_OUTPUT_DEVICE
        AudioDiagnosticEventKind.FIRST_WRITE_TO_HEAD_US -> AudioDiagnosticEvent.FIRST_WRITE_TO_HEAD_US
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
    val actualDevices: ActualAudioDevices = ActualAudioDevices(),
)

internal data class AudioDiagnosticSample(
    val timeMs: Long,
    val elapsedMs: Long,
    val windowMs: Long,
    val session: Long,
    val snapshot: AudioReceiveDiagnostics,
    val context: AudioDiagnosticContext,
    val voiceTiming: VoiceTimingWindow? = null,
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
