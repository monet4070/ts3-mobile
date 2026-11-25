package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
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

    private var visible = false
    private var target: InputTarget? = null
    private var startJob: Job? = null
    val state = trial.state
    val isInputReleased: Boolean get() = trial.isInputReleased

    fun watchSession() {
        scope.launch {
            session.collect { if (target != null && target != currentTarget()) stop() }
        }
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (!visible) stop()
    }

    fun start(settings: MicrophoneSettings) {
        val requested = currentTarget() ?: return
        if (!visible || !hasPermission() || startJob?.isActive == true || state.value.phase == MicrophoneTrialPhase.RUNNING) return
        target = requested
        startJob =
            scope.launch {
                val released = voiceInputReleased()
                if (!visible || target != requested || currentTarget() != requested || !hasPermission()) return@launch
                if (released) trial.start(settings) else trial.stop(MicrophoneTrialPhase.UNAVAILABLE)
            }
    }

    fun stop(): Boolean {
        startJob?.cancel()
        startJob = null
        target = null
        return trial.stop()
    }

    fun inputChanged() {
        if (target != null) stop()
    }

    private fun currentTarget(): InputTarget? {
        val current = session.value
        if (current.microphoneTrialAccess != MicrophoneTrialAccess.READY) return null
        return InputTarget(current.status.phase, current.bookmarkConnection?.token, current.audioRouting.selectedRouteId)
    }
}
