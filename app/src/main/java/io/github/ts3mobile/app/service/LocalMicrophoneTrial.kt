package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal enum class MicrophoneTrialAccess { READY, TURN_OFF_VOICE, UNAVAILABLE }

internal val TeamSpeakServiceState.microphoneTrialAccess: MicrophoneTrialAccess
    get() =
        when {
            audioRouting.switching -> MicrophoneTrialAccess.UNAVAILABLE
            status.phase.allowsOfflineActions -> MicrophoneTrialAccess.READY
            status.phase != ConnectionPhase.CONNECTED || bookmarkConnection == null -> MicrophoneTrialAccess.UNAVAILABLE
            microphoneMode != MicrophoneMode.OFF -> MicrophoneTrialAccess.TURN_OFF_VOICE
            else -> MicrophoneTrialAccess.READY
        }

internal class LocalMicrophoneTrial(
    private val scope: CoroutineScope,
    private val session: StateFlow<TeamSpeakServiceState>,
    private val trial: MicrophoneTrial,
    private val voiceInputReleased: suspend () -> Boolean,
    private val hasPermission: () -> Boolean,
) {
    private data class InputTarget(val phase: ConnectionPhase, val sessionToken: String?, val routeId: Int)

    private val lock = Any()
    private var visible = false
    private var target: InputTarget? = null
    private var startJob: Job? = null
    private var startGeneration = 0L
    val state = trial.state
    val isInputReleased: Boolean get() = trial.isInputReleased

    fun watchSession() {
        scope.launch {
            session.collect {
                val mismatched = synchronized(lock) { target != null && target != currentTarget() }
                if (mismatched) stop()
            }
        }
    }

    fun setVisible(visible: Boolean) {
        synchronized(lock) { this.visible = visible }
        if (!visible) stop()
    }

    fun start(settings: MicrophoneSettings) {
        val requested = currentTarget() ?: return
        val launched =
            synchronized(lock) {
                if (!visible || !hasPermission() || startJob?.isActive == true || state.value.phase == MicrophoneTrialPhase.RUNNING) {
                    return
                }
                val requestId = ++startGeneration
                target = requested
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val released = voiceInputReleased()
                        synchronized(lock) {
                            if (requestId == startGeneration) startJob = null
                            if (requestId != startGeneration || !visible || target != requested ||
                                currentTarget() != requested || !hasPermission()
                            ) {
                                return@synchronized
                            }
                            if (released) trial.start(settings) else trial.stop(MicrophoneTrialPhase.UNAVAILABLE)
                        }
                    } finally {
                        synchronized(lock) {
                            if (requestId == startGeneration) startJob = null
                        }
                    }
                }.also { startJob = it }
            }
        launched.start()
    }

    fun stop(): Boolean {
        cancelPendingStart()
        return trial.stop()
    }

    suspend fun stopAndAwait(): Boolean {
        cancelPendingStart()
        return trial.stopAndAwait()
    }

    fun inputChanged() {
        val hasTarget = synchronized(lock) { target != null }
        if (hasTarget) stop()
    }

    private fun cancelPendingStart() {
        val job =
            synchronized(lock) {
                startGeneration++
                target = null
                startJob.also { startJob = null }
            }
        job?.cancel()
    }

    private fun currentTarget(): InputTarget? {
        val current = session.value
        if (current.microphoneTrialAccess != MicrophoneTrialAccess.READY) return null
        return InputTarget(current.status.phase, current.bookmarkConnection?.token, current.audioRouting.selectedRouteId)
    }
}
