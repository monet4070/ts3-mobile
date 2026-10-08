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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Called once per attempt, before the socket is opened, so a diagnostics
     * journal can record attempts even when short-lived states are conflated.
     */
    fun onConnectionAttempt(reconnecting: Boolean) = Unit

    /** A new user-requested session starts a fresh recovery power budget. */
    fun onManualConnectionStarted() = Unit

    /**
     * Called only for the current listener once an attempt is confirmed
     * established. The service persists the connected server here.
     */
    fun onConnectionSucceeded(config: ServerConfig) = Unit

    /**
     * Called with the failure the coordinator is acting on, after the active
     * listener and session epoch have been checked. Detail stays protocol text;
     * callers must map it to a closed category before persisting it.
     */
    fun onConnectionFailure(status: ConnectionStatus) = Unit

    /** The transport closed without delivering a protocol failure callback. */
    fun onTransportDisconnected() = Unit

    /**
     * The connected watch did not execute for at least the gap threshold. This
     * reports watch scheduling only: it never infers a CPU, freeze or network
     * root cause and never changes connection behavior.
     */
    fun onWatchExecutionGap(gapSeconds: Long) = Unit
}

/**
 * Owns the session-epoch state (session, listener, restore target, jobs) and
 * the connection/reconnect state machine extracted from TeamSpeakService per
 * ADR-0005. The service keeps the binder, notification, diagnostics recorder
 * and Android component ownership; this class never touches a Service.
 */
internal class ConnectionCoordinator(
    private val host: ConnectionCoordinatorHost,
    // Monotonic milliseconds. The default stays JVM-only and does not count deep
    // sleep; the Android service injects SystemClock.elapsedRealtime().
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sessionFactory: () -> Ts3SessionClient = { Ts3jSessionClient() },
) : ReconnectEngine.Delegate {
    @Volatile
    private var session: Ts3SessionClient? = null

    private val listenerRef = AtomicReference<SessionListener?>(null)
    private var activeListener: SessionListener?
        get() = listenerRef.get()
        set(value) = listenerRef.set(value)

    @Volatile
    private var connectedOnce = false

    override var desiredConfig: ServerConfig? = null
        private set

    private var identityMaterial: String? = null
    private var connectionJob: Job? = null
    private var stableConnectionJob: Job? = null
    private var connectedWatchJob: Job? = null

    @Volatile
    private var livenessJob: Job? = null

    @Volatile
    private var lastNetworkState = DefaultNetworkState.Unknown
    private var lastAvailableNetworkGeneration: Long? = null
    private val reconnectEngine = ReconnectEngine(host, this)
    private val channelRestore = ChannelRestoreTracker()

    fun beginConnection(config: ServerConfig) {
        val epoch = host.sessionGeneration.beginSession()
        host.onManualConnectionStarted()
        connectedOnce = false
        reconnectEngine.resetAttemptCount()
        host.diagnosticsRecorder.resetSession()
        desiredConfig = config
        identityMaterial = null
        channelRestore.reset()
        activeListener = null
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
        reconnectEngine.cancel()
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
                backgroundRuntime = host.mutableState.value.backgroundRuntime,
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
                            reconnectEngine.launch(result.status, epoch)
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
        reconnectEngine.cancel()
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
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
                    if (host.mutableState.value.status.phase != ConnectionPhase.CONNECTED) {
                        throw NotConnectedException()
                    }
                    val active = session ?: throw NotConnectedException()
                    active.joinChannel(channelId, password)
                }
                channelRestore.rememberManualJoin(channelId, password)
                host.mutableState.update { state ->
                    state.copy(switchingChannelId = null, channelError = null)
                }
            } catch (error: Throwable) {
                host.diagnosticsRecorder.recordChannelJoinFailure()
                host.refreshDiagnostics()
                host.mutableState.update { state ->
                    state.copy(
                        switchingChannelId = null,
                        channelError =
                            if (error is NotConnectedException) {
                                UserMessage.NotConnected
                            } else {
                                UserMessage.ChannelJoinFailed(host.conciseMessage(error))
                            },
                    )
                }
            }
        }
    }

    /**
     * Identity-aware default-network update fed by [DefaultNetworkMonitor].
     *
     * A pending identity (capabilities not reported yet) is never a loss, so the
     * brief `onAvailable` -> `onCapabilitiesChanged` gap cannot force a healthy
     * session to reconnect. Confirmed availability loss reuses the existing
     * reconnect path; a confirmed identity change only triggers a bounded
     * liveness check, never a blind reconnect.
     */
    fun onNetworkChanged(state: DefaultNetworkState) {
        lastNetworkState = state
        if (state.isPending && !state.isBlocked) return
        if (!state.isAvailable) {
            lastAvailableNetworkGeneration = null
            onNetworkUnavailable()
            return
        }
        val previousAvailableGeneration = lastAvailableNetworkGeneration
        lastAvailableNetworkGeneration = state.generation
        if (previousAvailableGeneration != null && previousAvailableGeneration != state.generation) {
            verifySessionAfterNetworkSwitch(state.generation)
        }
    }

    /**
     * Boolean compatibility path for callers that do not track network identity
     * yet. Only a genuine availability transition advances the generation, so a
     * duplicate capability callback cannot be mistaken for a network switch.
     */
    fun onNetworkChanged(available: Boolean) {
        val previous = lastNetworkState
        val generation =
            if (available && !previous.isAvailable) previous.generation + 1 else previous.generation
        onNetworkChanged(
            DefaultNetworkState(
                isAvailable = available,
                isBlocked = previous.isBlocked,
                networkKey = previous.networkKey,
                generation = generation,
            ),
        )
    }

    private fun onNetworkUnavailable() {
        if (host.mutableState.value.status.phase == ConnectionPhase.CONNECTED) {
            activeListener?.takeIf { it.connected }?.let { listener ->
                onEstablishedSessionEnded(
                    listener,
                    ConnectionStatus(
                        ConnectionPhase.DISCONNECTED,
                        ConnectionLivenessPolicy.NETWORK_LOST_DETAIL,
                        retryable = true,
                    ),
                )
            }
        }
        if (host.mutableState.value.status.phase == ConnectionPhase.RECONNECTING) {
            host.mutableState.update { current ->
                current.withStatus(
                    ConnectionStatus(
                        ConnectionPhase.RECONNECTING,
                        retryable = true,
                    ),
                    UserMessage.WaitingForNetwork,
                )
            }
            host.updateNotification()
        }
    }

    /**
     * Verifies a session that survived a default-network switch with one bounded
     * control round trip. The result is discarded when the listener was replaced
     * or the user disconnected first, so a late probe can never revive a session
     * that is already gone.
     */
    private fun verifySessionAfterNetworkSwitch(generation: Long) {
        val listener = activeListener?.takeIf { it.connected } ?: return
        if (host.mutableState.value.status.phase != ConnectionPhase.CONNECTED) return
        val probeSession = session ?: return
        livenessJob?.cancel()
        livenessJob =
            host.serviceScope.launch {
                val alive =
                    try {
                        // withTimeoutOrNull bounds a suspend implementation; the
                        // blocking socket call keeps its own protocol timeout.
                        withTimeoutOrNull(ConnectionLivenessPolicy.PROBE_TIMEOUT_MS) {
                            probeSession.verifyLiveness(ConnectionLivenessPolicy.PROBE_TIMEOUT_MS)
                        } ?: false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        false
                    }
                if (!isActive) return@launch
                if (!isListenerActive(listener) || !listener.connected) return@launch
                // A second switch (B -> C) must not let a stale B probe end a
                // healthy C session.
                if (session !== probeSession) return@launch
                val current = lastNetworkState
                if (current.generation != generation || current.isPending || !current.isAvailable) return@launch
                if (!alive) {
                    onEstablishedSessionEnded(
                        listener,
                        ConnectionStatus(
                            ConnectionPhase.DISCONNECTED,
                            ConnectionLivenessPolicy.NETWORK_SWITCH_UNVERIFIED_DETAIL,
                            retryable = true,
                        ),
                    )
                }
            }
    }

    fun close() {
        host.sessionGeneration.invalidate()
        activeListener = null
        connectionJob?.cancel()
        reconnectEngine.cancel()
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
        session?.close()
        session = null
    }

    override suspend fun performAttempt(epoch: Long): AttemptResult {
        val config = desiredConfig ?: return AttemptResult.Stale
        return performConnectionAttempt(config, epoch, reconnecting = true)
    }

    private suspend fun performConnectionAttempt(
        config: ServerConfig,
        epoch: Long,
        reconnecting: Boolean,
    ): AttemptResult =
        host.sessionMutex.withLock {
            if (!isEpochActive(epoch)) return@withLock AttemptResult.Stale

            host.diagnosticsRecorder.recordConnectionAttempt(reconnecting)
            host.onConnectionAttempt(reconnecting)
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
                    if (reconnecting) {
                        current.withStatus(
                            ConnectionStatus(
                                ConnectionPhase.RECONNECTING,
                                status.detail,
                                retryable = status.retryable,
                            ),
                            UserMessage.ReconnectFailed(status.detail?.takeIf(String::isNotBlank)),
                        )
                    } else {
                        current.withStatus(status)
                    }.copy(snapshot = SessionSnapshot.Empty)
                }
                host.diagnosticsRecorder.recordConnectionFailure(status.retryable)
                host.onConnectionFailure(status)
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
                connectedWatchJob?.cancel()
                host.mutableState.value =
                    TeamSpeakServiceState(
                        diagnostics = host.diagnosticsRecorder.snapshot(),
                        backgroundRuntime = host.mutableState.value.backgroundRuntime,
                    )
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
                    if (!reconnecting) host.mutableState.update { it.withStatus(status) }
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
                            current.withStatus(status).copy(
                                snapshot = SessionSnapshot.Empty,
                                isTransmitting = false,
                            )
                        }
                    }
                }

                ConnectionPhase.DISCONNECTING -> {
                    host.mutableState.update { it.withStatus(status) }
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
                channelRestore.rememberSnapshot(snapshot.currentChannelId)
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
            current.withStatus(ConnectionStatus(ConnectionPhase.CONNECTED)).copy(
                switchingChannelId = null,
                channelError = null,
            )
        }
        desiredConfig?.let { host.onConnectionSucceeded(it) }
        channelRestore.seedFromCurrentChannel(host.mutableState.value.snapshot.currentChannelId)
        scheduleStableConnectionReset(listener)
        startConnectedWatch(listener)
        host.refreshDiagnostics()
        host.updateNotification()
        host.reconcileMicrophone()
        restoreLastChannelIfNeeded(listener)
    }

    private fun onEstablishedSessionEnded(
        listener: SessionListener,
        status: ConnectionStatus,
        transportSession: Ts3SessionClient? = null,
    ) {
        if (transportSession != null && session !== transportSession) return
        if (!isEpochActive(listener.epoch) || !listenerRef.compareAndSet(listener, null)) return
        if (!isEpochActive(listener.epoch)) return
        if (transportSession != null) host.onTransportDisconnected()
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
        host.diagnosticsRecorder.recordConnectionFailure(status.retryable)
        host.onConnectionFailure(status)
        host.refreshDiagnostics()
        host.stopMicrophoneImmediately()
        host.stopPlayback()

        if (status.retryable && connectedOnce && !host.sessionGeneration.isDisconnectRequested) {
            reconnectEngine.launch(status, listener.epoch)
        } else {
            host.serviceScope.launch { finishTerminalFailure(status, listener.epoch) }
        }
    }

    override suspend fun suspendAudioForReconnect() {
        host.stopMicrophoneCapture()
        host.stopPlayback()
    }

    override suspend fun finishTerminalFailure(
        status: ConnectionStatus,
        epoch: Long,
    ) {
        if (!isEpochActive(epoch)) return
        activeListener?.takeIf { it.epoch == epoch }?.let { listenerRef.compareAndSet(it, null) }
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
        host.sessionMutex.withLock {
            if (!isEpochActive(epoch)) return@withLock
            session?.close()
            session = null
        }
        host.stopMicrophoneCapture()
        host.stopPlayback()
        host.stopAudioRouting()
        host.mutableState.update { current ->
            current.withStatus(status.copy(retryable = false)).copy(
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
                if (isListenerActive(listener) && listener.connected) {
                    reconnectEngine.resetAttemptCount()
                }
            }
    }

    private fun startConnectedWatch(listener: SessionListener) {
        connectedWatchJob?.cancel()
        connectedWatchJob =
            host.serviceScope.launch {
                var previousTickMs = clockMs()
                while (isActive && isListenerActive(listener)) {
                    // Forward progress of this watch is the only local evidence
                    // that the process is still being scheduled. A gap is
                    // reported as data and never inferred as a specific cause.
                    val nowMs = clockMs()
                    val gapMs = nowMs - previousTickMs
                    previousTickMs = nowMs
                    if (gapMs >= WATCH_EXECUTION_GAP_THRESHOLD_MS && isListenerActive(listener)) {
                        host.onWatchExecutionGap(gapMs / MILLIS_PER_SECOND)
                    }
                    // ts3j can silently transition to DISCONNECTED after its
                    // PONG/ACK timeout. Observe that local state without sending
                    // extra keepalives or treating channel silence as failure.
                    val observedSession = session ?: return@launch
                    if (observedSession.transportConnected == false &&
                        session === observedSession && isListenerActive(listener)
                    ) {
                        // Ending the session cancels this watch. Keep this
                        // transition synchronous; recovery has its own job.
                        onEstablishedSessionEnded(
                            listener,
                            ConnectionStatus(
                                ConnectionPhase.ERROR,
                                ConnectionLivenessPolicy.TRANSPORT_LOST_DETAIL,
                                retryable = true,
                            ),
                            transportSession = observedSession,
                        )
                        return@launch
                    }
                    host.refreshDiagnostics()
                    delay(CONNECTED_WATCH_INTERVAL_MS)
                }
            }
    }

    private fun restoreLastChannelIfNeeded(listener: SessionListener) {
        val target =
            channelRestore.targetToRestore(host.mutableState.value.snapshot.currentChannelId)
                ?: return
        channelRestore.beginRestore()
        host.mutableState.update { it.copy(switchingChannelId = target.channelId) }
        host.serviceScope.launch {
            try {
                host.sessionMutex.withLock {
                    if (!isListenerActive(listener) || !listener.connected) {
                        throw SessionExpiredException()
                    }
                    val active = session ?: throw SessionExpiredException()
                    active.joinChannel(target.channelId, target.password)
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
                            channelError =
                                if (error is SessionExpiredException) {
                                    UserMessage.SessionExpired
                                } else {
                                    UserMessage.ChannelRestoreFailed(host.conciseMessage(error))
                                },
                        )
                    }
                }
            } finally {
                channelRestore.endRestore()
            }
        }
    }

    private fun isListenerActive(listener: SessionListener): Boolean = activeListener === listener && isEpochActive(listener.epoch)

    private fun isEpochActive(epoch: Long): Boolean = host.sessionGeneration.isActive(epoch)

    private companion object {
        const val STABLE_CONNECTION_MS = 30_000L
        const val CONNECTED_WATCH_INTERVAL_MS = 1_000L
        const val WATCH_EXECUTION_GAP_THRESHOLD_MS = 10_000L
        const val MILLIS_PER_SECOND = 1_000L

        val interruptibleConnectionPhases =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.RECONNECTING,
            )
    }
}

/** Outcome of one connection attempt, consumed by the coordinator and the reconnect engine. */
internal sealed interface AttemptResult {
    data object Success : AttemptResult

    data object Stale : AttemptResult

    data class Failed(
        val status: ConnectionStatus,
        val retryable: Boolean,
    ) : AttemptResult
}
