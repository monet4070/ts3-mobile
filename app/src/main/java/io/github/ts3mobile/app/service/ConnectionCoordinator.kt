package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Service-side collaborators the connection state machine needs, exposed as
 * behavior-level operations instead of component references so the
 * coordinator can be unit-tested on the JVM with a fake host.
 */
internal interface ConnectionCoordinatorHost {
    val serviceScope: CoroutineScope
    val sessionMutex: Mutex
    val sessionGeneration: SessionGeneration
    val mutableState: MutableStateFlow<TeamSpeakServiceState>
    val reconnectPolicy: ReconnectPolicy
    val diagnosticsRecorder: DiagnosticsRecorder

    fun conciseMessage(error: Throwable): String

    fun updateNotification()

    fun refreshDiagnostics()

    fun resetParticipantGains()

    fun resetMicrophoneForConnection()

    suspend fun stopMicrophoneCapture()

    suspend fun createIdentity(): String

    fun startAudioRouting()

    fun stopAudioRouting()

    fun applySelectedPlaybackMuted()

    fun startPlayback()

    fun stopPlayback()

    fun clearPlaybackMuted()

    fun attachMicrophoneTo(session: Ts3SessionClient)

    fun stopMicrophoneImmediately()

    fun reconcileMicrophone()

    fun applyParticipantAudioSettings(snapshot: SessionSnapshot)

    fun submitVoiceFrame(frame: VoiceFrame)

    fun removeForegroundNotification()

    fun requestStopSelf()
}

/**
 * Owns the session-epoch state and the connection/reconnect state machine.
 * This step only introduces the seam; the state machine moves here from
 * TeamSpeakService in the next step, so both entry points are no-ops.
 */
internal class ConnectionCoordinator(private val host: ConnectionCoordinatorHost) {
    fun beginConnection(config: ServerConfig) = Unit

    fun requestDisconnect() = Unit
}
