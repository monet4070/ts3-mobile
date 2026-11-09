package io.github.ts3mobile.app.service

import android.os.SystemClock
import io.github.ts3mobile.audio.opus.AudioDiagnosticEventKind
import io.github.ts3mobile.audio.opus.AudioDiagnosticsObserver
import io.github.ts3mobile.audio.opus.AudioReceiveDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One sampling owner per player. Shutdown callbacks keep the process-owned writer alive. */
internal class AudioDiagnosticController(
    private val repository: AudioDiagnosticRepository,
    private val takeSnapshot: () -> AudioReceiveDiagnostics,
    private val context: () -> AudioDiagnosticContext,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) : AudioDiagnosticsObserver {
    private var generation = 0L

    @Volatile private var session = 0L
    private var lastSampleElapsed = 0L

    @Volatile private var ended = false
    private var observedContext: AudioDiagnosticContext? = null
    private var sampling: Job? = null

    fun start(scope: CoroutineScope) {
        sampling =
            scope.launch {
                while (isActive) {
                    sampleNow()
                    delay(SAMPLE_INTERVAL_MS)
                }
            }
    }

    @Synchronized fun sampleNow(force: Boolean = false) {
        if (!force && session != 0L && !ended && elapsedMs() - lastSampleElapsed < SAMPLE_INTERVAL_MS) return
        accept(takeSnapshot(), final = false)
    }

    @Synchronized override fun onStarted(snapshot: AudioReceiveDiagnostics) {
        accept(snapshot, final = false)
    }

    @Synchronized override fun onStopped(snapshot: AudioReceiveDiagnostics) {
        accept(snapshot, final = true)
    }

    override fun onEvent(
        kind: AudioDiagnosticEventKind,
        value: Long,
    ) {
        if (session == 0L || ended) return
        repository.event(session, AudioDiagnosticEvent.valueOf(kind.name), value)
    }

    @Synchronized fun observe(value: AudioDiagnosticContext) {
        val previous = observedContext
        observedContext = value
        if (session == 0L || ended || previous == value) return
        if (previous?.selectedRoute != value.selectedRoute) {
            repository.event(
                session,
                AudioDiagnosticEvent.SELECTED_ROUTE,
                value.selectedRoute.ordinal.toLong(),
            )
        }
        if (previous?.routeError != value.routeError) {
            repository.event(
                session,
                AudioDiagnosticEvent.ROUTE_ERROR,
                value.routeError?.ordinal?.toLong() ?: -1,
            )
        }
        if (previous?.safetyMuted != value.safetyMuted) {
            repository.event(
                session,
                AudioDiagnosticEvent.SAFETY_MUTE,
                if (value.safetyMuted) 1 else 0,
            )
        }
        if (previous?.switching != value.switching) {
            repository.event(
                session,
                AudioDiagnosticEvent.ROUTE_SWITCHING,
                if (value.switching) 1 else 0,
            )
        }
        if (previous?.network != value.network || previous.networkGeneration != value.networkGeneration) {
            repository.event(session, AudioDiagnosticEvent.NETWORK, value.network.ordinal.toLong())
        }
    }

    fun serviceStopped() {
        sampling?.cancel()
        sampleNow(force = true)
        // onStopped owns the final sample/END, including a worker retiring after onDestroy.
    }

    private fun accept(
        snapshot: AudioReceiveDiagnostics,
        final: Boolean,
    ) {
        if (snapshot.generation == 0L) return
        if (snapshot.generation != generation) {
            if (snapshot.generation < generation) return
            if (session != 0L && !ended) repository.end(session, AudioDiagnosticEvent.SERVICE_STOPPED)
            generation = snapshot.generation
            session = repository.begin()
            lastSampleElapsed = elapsedMs()
            ended = false
        }
        if (ended) return
        val now = elapsedMs()
        val current = context()
        observe(current)
        repository.sample(AudioDiagnosticSample(wallMs(), now, (now - lastSampleElapsed).coerceAtLeast(0), session, snapshot, current))
        lastSampleElapsed = now
        if (final || !snapshot.active) {
            repository.end(session)
            ended = true
        }
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 5_000L
    }
}

internal fun audioDiagnosticContext(
    state: TeamSpeakServiceState,
    network: DefaultNetworkState,
): AudioDiagnosticContext =
    AudioDiagnosticContext(
        state.status.phase, state.playbackStatus, state.audioRouting.selectedRoute.kind,
        state.audioRouting.switching, state.audioRouting.safetyMuted, state.playbackMuted,
        state.audioRouting.error?.kind, network.transport, network.generation, state.backgroundRuntime.cpuLockHeld,
    )
