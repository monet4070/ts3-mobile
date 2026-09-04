package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.Ts3jSessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import io.github.ts3mobile.protocol.isRetryableConnectionFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service-side collaborators the connection state machine needs, exposed as
 * behavior-level operations instead of component references so the
 * coordinator can be unit-tested on the JVM with a fake host.
 */
internal interface ConnectionCoordinatorHost {
    val serviceScope: CoroutineScope
    val sessionMutex: Mutex
    val sessionGeneration: SessionGeneration
    val networkAvailable: StateFlow<Boolean>
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
 * Owns the session-epoch state (session, listener, restore target, jobs) and
 * the connection/reconnect state machine extracted from TeamSpeakService per
 * ADR-0005. The service keeps the binder, notification, diagnostics recorder
 * and Android component ownership; this class never touches a Service.
 */
internal class ConnectionCoordinator(
    private val host: ConnectionCoordinatorHost,
    private val sessionFactory: () -> Ts3SessionClient = { Ts3jSessionClient() },
) {
    @Volatile
    private var session: Ts3SessionClient? = null

    @Volatile
    private var activeListener: SessionListener? = null

    @Volatile
    private var connectedOnce = false

    @Volatile
    private var restorePending = false

    private var desiredConfig: ServerConfig? = null
    private var identityMaterial: String? = null
    private var lastChannel: ChannelRestoreTarget? = null
    private var reconnectAttempt = 0
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var stableConnectionJob: Job? = null
    private var diagnosticsRefreshJob: Job? = null

    fun beginConnection(config: ServerConfig) {
        val epoch = host.sessionGeneration.beginSession()
        connectedOnce = false
        reconnectAttempt = 0
        host.diagnosticsRecorder.resetSession()
        desiredConfig = config
        identityMaterial = null
        lastChannel = null
        restorePending = false
        activeListener = null
        stableConnectionJob?.cancel()
        reconnectJob?.cancel()
        connectionJob?.cancel()

        val selectedMicrophoneMode = host.mutableState.value.microphoneMode
        val selectedPlaybackMuted = host.mutableState.value.playbackMuted
        host.resetParticipantGains()
        host.resetMicrophoneForConnection()
        host.mutableState.value =
            TeamSpeakServiceState(
                status = ConnectionStatus(ConnectionPhase.CONNECTING),
                serverLabel = "${config.host}:${config.port}",
                microphoneMode = selectedMicrophoneMode,
                playbackMuted = selectedPlaybackMuted,
                audioRouting = host.mutableState.value.audioRouting,
                diagnostics = host.diagnosticsRecorder.snapshot(),
            )
        if (session != null) session?.close()

        connectionJob =
            host.serviceScope.launch {
                host.stopMicrophoneCapture()
                when (val result = performConnectionAttempt(config, epoch, reconnecting = false)) {
                    AttemptResult.Success,
                    AttemptResult.Stale,
                    -> Unit

                    is AttemptResult.Failed -> {
                        if (connectedOnce && result.retryable) {
                            launchReconnect(result.status, epoch)
                        } else {
                            finishTerminalFailure(result.status, epoch)
                        }
                    }
                }
            }
    }

    fun requestDisconnect() {
        host.sessionGeneration.invalidate()
        activeListener = null
        connectionJob?.cancel()
        reconnectJob?.cancel()
        stableConnectionJob?.cancel()
        diagnosticsRefreshJob?.cancel()
        if (host.mutableState.value.status.phase in interruptibleConnectionPhases) {
            session?.close()
        }
        host.serviceScope.launch { disconnect(userInitiated = true) }
    }

    fun joinChannel(
        channelId: Int,
        password: String,
    ) {
        val current = host.mutableState.value
        if (current.status.phase != ConnectionPhase.CONNECTED) return
        if (current.snapshot.currentChannelId == channelId) return
        host.diagnosticsRecorder.recordChannelJoinAttempt()
        host.refreshDiagnostics()
        host.mutableState.update {
            it.copy(switchingChannelId = channelId, channelError = null)
        }
        host.serviceScope.launch {
            try {
                host.sessionMutex.withLock {
                    check(host.mutableState.value.status.phase == ConnectionPhase.CONNECTED) {
                        "当前未连接到服务器"
                    }
                    session?.joinChannel(channelId, password)
                        ?: error("当前未连接到服务器")
                }
                lastChannel = ChannelRestoreTarget(channelId, password)
                host.mutableState.update { state ->
                    state.copy(switchingChannelId = null, channelError = null)
                }
            } catch (error: Throwable) {
                host.diagnosticsRecorder.recordChannelJoinFailure()
                host.refreshDiagnostics()
                host.mutableState.update { state ->
                    state.copy(
                        switchingChannelId = null,
                        channelError = "切换频道失败：${host.conciseMessage(error)}",
                    )
                }
            }
        }
    }

    fun onNetworkChanged(available: Boolean) {
        if (!available && host.mutableState.value.status.phase == ConnectionPhase.CONNECTED) {
            activeListener?.takeIf { it.connected }?.let { listener ->
                onEstablishedSessionEnded(
                    listener,
                    ConnectionStatus(
                        ConnectionPhase.DISCONNECTED,
                        "网络连接已断开",
                        retryable = true,
                    ),
                )
            }
        }
        if (!available && host.mutableState.value.status.phase == ConnectionPhase.RECONNECTING) {
            host.mutableState.update { current ->
                current.copy(
                    status =
                        ConnectionStatus(
                            ConnectionPhase.RECONNECTING,
                            WAITING_FOR_NETWORK_DETAIL,
                            retryable = true,
                        ),
                )
            }
            host.updateNotification()
        }
    }

    fun close() {
        host.sessionGeneration.invalidate()
        activeListener = null
        connectionJob?.cancel()
        reconnectJob?.cancel()
        stableConnectionJob?.cancel()
        diagnosticsRefreshJob?.cancel()
        session?.close()
        session = null
    }

    private suspend fun performConnectionAttempt(
        config: ServerConfig,
        epoch: Long,
        reconnecting: Boolean,
    ): AttemptResult =
        host.sessionMutex.withLock {
            if (!isEpochActive(epoch)) return@withLock AttemptResult.Stale

            host.diagnosticsRecorder.recordConnectionAttempt(reconnecting)
            host.refreshDiagnostics()

            activeListener = null
            session?.close()
            session = null

            try {
                host.startAudioRouting()
                val identity =
                    identityMaterial ?: host.createIdentity().also {
                        identityMaterial = it
                    }
                host.mutableState.update { it.copy(identityReady = true) }

                host.applySelectedPlaybackMuted()
                host.resetParticipantGains()
                host.startPlayback()

                val newSession = sessionFactory()
                val listener = SessionListener(epoch, reconnecting)
                activeListener = listener
                session = newSession
                host.attachMicrophoneTo(newSession)
                newSession.connect(config, identity, listener)
                if (!isListenerActive(listener)) {
                    newSession.close()
                    if (session === newSession) session = null
                    return@withLock if (isEpochActive(epoch)) {
                        val status = host.mutableState.value.status
                        AttemptResult.Failed(status, status.retryable)
                    } else {
                        AttemptResult.Stale
                    }
                }
                if (host.mutableState.value.status.phase == ConnectionPhase.CONNECTED) {
                    AttemptResult.Success
                } else {
                    val status = host.mutableState.value.status
                    AttemptResult.Failed(status, status.retryable && reconnecting)
                }
            } catch (error: Throwable) {
                val failedSession = session
                activeListener = null
                failedSession?.close()
                if (session === failedSession) session = null
                if (!isEpochActive(epoch)) return@withLock AttemptResult.Stale

                val status =
                    ConnectionStatus(
                        ConnectionPhase.ERROR,
                        host.conciseMessage(error),
                        retryable = error.isRetryableConnectionFailure(),
                    )
                host.mutableState.update { current ->
                    current.copy(
                        status =
                            if (reconnecting) {
                                ConnectionStatus(
                                    ConnectionPhase.RECONNECTING,
                                    "重连失败：${status.detail.orEmpty()}".trimEnd('：'),
                                    retryable = status.retryable,
                                )
                            } else {
                                status
                            },
                        snapshot = SessionSnapshot.Empty,
                    )
                }
                host.diagnosticsRecorder.recordConnectionFailure(status.retryable)
                host.refreshDiagnostics()
                AttemptResult.Failed(status, status.retryable && reconnecting)
            }
        }

    private suspend fun disconnect(userInitiated: Boolean) =
        host.sessionMutex.withLock {
            host.stopMicrophoneCapture()
            try {
                session?.disconnect(if (userInitiated) "Disconnected by user" else "Service stopped")
            } finally {
                session?.close()
                session = null
                activeListener = null
                host.stopPlayback()
                host.clearPlaybackMuted()
                host.stopAudioRouting()
                diagnosticsRefreshJob?.cancel()
                host.mutableState.value = TeamSpeakServiceState()
                host.removeForegroundNotification()
                if (userInitiated) host.requestStopSelf()
            }
        }

    private inner class SessionListener(
        val epoch: Long,
        private val reconnecting: Boolean,
    ) : Ts3SessionListener {
        @Volatile
        var connected = false

        override fun onStatusChanged(status: ConnectionStatus) {
            if (!isListenerActive(this)) return
            when (status.phase) {
                ConnectionPhase.CONNECTING -> {
                    if (!reconnecting) host.mutableState.update { it.copy(status = status) }
                }

                ConnectionPhase.CONNECTED -> {
                    connected = true
                    onSessionConnected(this)
                }

                ConnectionPhase.DISCONNECTED,
                ConnectionPhase.ERROR,
                -> {
                    if (connected) {
                        onEstablishedSessionEnded(this, status)
                    } else {
                        host.mutableState.update { current ->
                            current.copy(
                                status = status,
                                snapshot = SessionSnapshot.Empty,
                                isTransmitting = false,
                            )
                        }
                    }
                }

                ConnectionPhase.DISCONNECTING -> {
                    host.mutableState.update { it.copy(status = status) }
                    host.updateNotification()
                }

                ConnectionPhase.RECONNECTING -> Unit
            }
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            if (!isListenerActive(this)) return
            host.mutableState.update { it.copy(snapshot = snapshot) }
            host.applyParticipantAudioSettings(snapshot)
            if (connected) {
                lastChannel =
                    ChannelRestorePolicy.afterSnapshot(
                        remembered = lastChannel,
                        observedChannelId = snapshot.currentChannelId,
                        restorePending = restorePending,
                    )
            }
            host.updateNotification()
        }

        override fun onVoiceFrame(frame: VoiceFrame) {
            if (!isListenerActive(this) || !connected) {
                host.diagnosticsRecorder.recordVoiceFrameDropped()
                return
            }
            host.diagnosticsRecorder.recordVoiceFrameReceived()
            host.submitVoiceFrame(frame)
        }
    }

    private fun onSessionConnected(listener: SessionListener) {
        if (!isListenerActive(listener)) return
        connectedOnce = true
        host.diagnosticsRecorder.recordConnectionSuccess()
        host.mutableState.update { current ->
            current.copy(
                status = ConnectionStatus(ConnectionPhase.CONNECTED),
                switchingChannelId = null,
                channelError = null,
            )
        }
        if (lastChannel == null) {
            host.mutableState.value.snapshot.currentChannelId?.let {
                lastChannel = ChannelRestoreTarget(it, "")
            }
        }
        scheduleStableConnectionReset(listener)
        startDiagnosticsRefresh(listener)
        host.refreshDiagnostics()
        host.updateNotification()
        host.reconcileMicrophone()
        restoreLastChannelIfNeeded(listener)
    }

    private fun onEstablishedSessionEnded(
        listener: SessionListener,
        status: ConnectionStatus,
    ) {
        if (!isListenerActive(listener)) return
        activeListener = null
        stableConnectionJob?.cancel()
        diagnosticsRefreshJob?.cancel()
        host.diagnosticsRecorder.recordConnectionFailure(status.retryable)
        host.refreshDiagnostics()
        host.stopMicrophoneImmediately()
        host.stopPlayback()

        if (status.retryable && connectedOnce && !host.sessionGeneration.isDisconnectRequested) {
            launchReconnect(status, listener.epoch)
        } else {
            host.serviceScope.launch { finishTerminalFailure(status, listener.epoch) }
        }
    }

    private fun launchReconnect(
        cause: ConnectionStatus,
        epoch: Long,
    ) {
        if (!isEpochActive(epoch)) return
        val detail =
            if (host.networkAvailable.value) {
                "连接中断，准备自动重连：${cause.detail.orEmpty()}".trimEnd('：')
            } else {
                WAITING_FOR_NETWORK_DETAIL
            }
        host.mutableState.update { current ->
            current.copy(
                status =
                    ConnectionStatus(
                        ConnectionPhase.RECONNECTING,
                        detail,
                        retryable = true,
                    ),
                snapshot = SessionSnapshot.Empty,
                isTransmitting = false,
                switchingChannelId = null,
            )
        }
        host.refreshDiagnostics()
        host.updateNotification()
        if (reconnectJob?.isActive == true) return

        val job =
            host.serviceScope.launch {
                suspendAudioForReconnect()
                reconnectLoop(epoch)
            }
        reconnectJob = job
        job.invokeOnCompletion {
            if (reconnectJob === job) reconnectJob = null
        }
    }

    private suspend fun reconnectLoop(epoch: Long) {
        val config = desiredConfig ?: return
        while (isEpochActive(epoch)) {
            if (!host.networkAvailable.value) {
                host.mutableState.update { current ->
                    current.copy(
                        status =
                            ConnectionStatus(
                                ConnectionPhase.RECONNECTING,
                                WAITING_FOR_NETWORK_DETAIL,
                                retryable = true,
                            ),
                    )
                }
                host.updateNotification()
                host.networkAvailable.first { it }
            }
            if (!isEpochActive(epoch)) return

            val attempt = reconnectAttempt + 1
            val delayMs = host.reconnectPolicy.delayForAttempt(attempt)
            host.mutableState.update { current ->
                current.copy(
                    status =
                        ConnectionStatus(
                            ConnectionPhase.RECONNECTING,
                            "${delayMs / 1_000} 秒后进行第 $attempt 次重连",
                            retryable = true,
                        ),
                )
            }
            host.updateNotification()
            delay(delayMs)
            if (!host.networkAvailable.value) continue
            if (!isEpochActive(epoch)) return

            reconnectAttempt = attempt
            host.mutableState.update { current ->
                current.copy(
                    status =
                        ConnectionStatus(
                            ConnectionPhase.RECONNECTING,
                            "正在进行第 $attempt 次重连",
                            retryable = true,
                        ),
                )
            }
            host.updateNotification()

            when (val result = performConnectionAttempt(config, epoch, reconnecting = true)) {
                AttemptResult.Success -> return
                AttemptResult.Stale -> return
                is AttemptResult.Failed -> {
                    if (!result.retryable) {
                        finishTerminalFailure(result.status, epoch)
                        return
                    }
                    suspendAudioForReconnect()
                }
            }
        }
    }

    private suspend fun suspendAudioForReconnect() {
        host.stopMicrophoneCapture()
        host.stopPlayback()
    }

    private suspend fun finishTerminalFailure(
        status: ConnectionStatus,
        epoch: Long,
    ) {
        if (!isEpochActive(epoch)) return
        activeListener = null
        stableConnectionJob?.cancel()
        diagnosticsRefreshJob?.cancel()
        host.sessionMutex.withLock {
            if (!isEpochActive(epoch)) return@withLock
            session?.close()
            session = null
        }
        host.stopMicrophoneCapture()
        host.stopPlayback()
        host.stopAudioRouting()
        host.mutableState.update { current ->
            current.copy(
                status = status.copy(retryable = false),
                snapshot = SessionSnapshot.Empty,
                isTransmitting = false,
                switchingChannelId = null,
            )
        }
        host.refreshDiagnostics()
        host.removeForegroundNotification()
        host.requestStopSelf()
    }

    private fun scheduleStableConnectionReset(listener: SessionListener) {
        stableConnectionJob?.cancel()
        stableConnectionJob =
            host.serviceScope.launch {
                delay(STABLE_CONNECTION_MS)
                if (isListenerActive(listener) && listener.connected) reconnectAttempt = 0
            }
    }

    private fun startDiagnosticsRefresh(listener: SessionListener) {
        diagnosticsRefreshJob?.cancel()
        diagnosticsRefreshJob =
            host.serviceScope.launch {
                while (isActive && isListenerActive(listener)) {
                    host.refreshDiagnostics()
                    delay(DIAGNOSTICS_REFRESH_MS)
                }
            }
    }

    private fun restoreLastChannelIfNeeded(listener: SessionListener) {
        val target =
            ChannelRestorePolicy.targetToRestore(
                remembered = lastChannel,
                currentChannelId = host.mutableState.value.snapshot.currentChannelId,
            ) ?: return
        restorePending = true
        host.mutableState.update { it.copy(switchingChannelId = target.channelId) }
        host.serviceScope.launch {
            try {
                host.sessionMutex.withLock {
                    check(isListenerActive(listener) && listener.connected) { "连接已失效" }
                    session?.joinChannel(target.channelId, target.password) ?: error("连接已失效")
                }
                host.mutableState.update {
                    it.copy(switchingChannelId = null, channelError = null)
                }
            } catch (error: Throwable) {
                if (isEpochActive(listener.epoch)) {
                    host.diagnosticsRecorder.recordChannelJoinFailure()
                    host.refreshDiagnostics()
                    host.mutableState.update {
                        it.copy(
                            switchingChannelId = null,
                            channelError = "恢复频道失败：${host.conciseMessage(error)}",
                        )
                    }
                }
            } finally {
                restorePending = false
            }
        }
    }

    private fun isListenerActive(listener: SessionListener): Boolean = activeListener === listener && isEpochActive(listener.epoch)

    private fun isEpochActive(epoch: Long): Boolean = host.sessionGeneration.isActive(epoch)

    private sealed interface AttemptResult {
        data object Success : AttemptResult

        data object Stale : AttemptResult

        data class Failed(
            val status: ConnectionStatus,
            val retryable: Boolean,
        ) : AttemptResult
    }

    private companion object {
        const val STABLE_CONNECTION_MS = 30_000L
        const val WAITING_FOR_NETWORK_DETAIL = "网络不可用，恢复后自动重连"
        const val DIAGNOSTICS_REFRESH_MS = 1_000L
        val interruptibleConnectionPhases =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.RECONNECTING,
            )
    }
}
