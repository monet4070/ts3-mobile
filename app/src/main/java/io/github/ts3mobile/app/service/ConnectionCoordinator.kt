package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.Ts3jSessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import io.github.ts3mobile.protocol.connectionFailureCategory
import io.github.ts3mobile.protocol.isRetryableConnectionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
     * Records one fixed-category journal event. Categories and numeric results
     * only: no message text, address, nickname, identity or password may reach it.
     */
    fun recordDiagnostic(
        kind: DiagnosticEventKind,
        code: Long = 0L,
    ) = Unit

    /**
     * Removes the foreground notification and stops the service while [exitToken]
     * is still the newest exit request.
     *
     * The service performs the ownership check and both side effects inside one
     * main-thread action, so an exit that a newer connect or a newer exit already
     * replaced cannot stop the service under it. The default keeps hosts without a
     * main-thread seam working and delegates to the unconditional teardown.
     */
    fun finishExitOnMainThread(exitToken: Long) {
        removeForegroundNotification()
        requestStopSelf()
    }

    /** Re-checks the failed session epoch in the main-thread teardown action. */
    fun finishTerminalOnMainThread(epoch: Long) {
        removeForegroundNotification()
        requestStopSelf()
    }

    /**
     * Removes the foreground notification after a bounded recovery gave up.
     *
     * The service re-checks [epoch] on the main thread and keeps running, so the
     * user can still read the explanation and retry manually.
     */
    fun removeForegroundNotificationOnMainThread(epoch: Long) = removeForegroundNotification()

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

    private val ownership = SessionRequestOwnership()
    private val exitLock = Any()
    private var exitJob: Job? = null
    private var activeExitToken: Long? = null
    private val exitJobs = mutableSetOf<Job>()

    @Volatile
    private var closed = false
    private val exitRunner = SessionExitRunner(host, ownership, clockMs) { closed }

    @Volatile
    private var exitSession: Ts3SessionClient? = null

    /** True while [exitToken] is still the newest exit request. */
    fun isLatestExit(exitToken: Long): Boolean = ownership.isLatestExit(exitToken)

    @Volatile
    private var livenessJob: Job? = null

    @Volatile
    private var lastNetworkState = DefaultNetworkState.Unknown
    private var lastAvailableNetworkGeneration: Long? = null
    private val reconnectEngine = ReconnectEngine(host, this)

    @Volatile
    private var refusedEpoch: Long? = null
    private val channelRestore = ChannelRestoreTracker()

    fun beginConnection(config: ServerConfig) {
        if (closed) return
        // Registered first and synchronously: a connect always takes ownership away
        // from an exit that is still finishing, so that exit can no longer stop the
        // service or clear this request's state.
        ownership.beginConnect()
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
        // A session captured by an in-flight exit is already out of `session`, so
        // this never closes the socket that exit is still using.
        val previousSession = synchronized(exitLock) { session.also { session = null } }
        runCatching { previousSession?.close() }

        connectionJob =
            host.serviceScope.launch {
                // Request order is established before IO scheduling, not by which
                // coroutine happens to acquire the session mutex first.
                synchronized(exitLock) { exitJob }?.join()
                if (!isEpochActive(epoch)) return@launch
                host.stopMicrophoneCapture()
                when (val result = performConnectionAttempt(config, epoch, reconnecting = false)) {
                    AttemptResult.Success,
                    AttemptResult.Stale,
                    -> Unit

                    is AttemptResult.Failed -> {
                        when {
                            connectedOnce && result.retryable -> reconnectEngine.launch(result.status, epoch)
                            else -> finishTerminalFailure(result.status, epoch)
                        }
                    }
                }
            }
    }

    /** User-visible disconnect; the same single exit as task removal. */
    fun requestDisconnect() = requestExit(ExitTrigger.USER_DISCONNECT)

    /**
     * Starts the one bounded exit for the current session.
     *
     * Everything up to the job launch runs synchronously on the caller's thread —
     * the main thread for task removal and notification actions — so the exit is
     * registered before any IO starts and a connect that arrives immediately after
     * is registered after it and owns the service again.
     *
     * The captured session leaves [session] here: from this point only this exit
     * may touch it, so [beginConnection] cannot close the socket it is using and
     * cannot cancel this job. Reconnect, liveness, the stable-connection reset and
     * the connected watch are cancelled at once, and no further voice frame can
     * leave the captured socket. The blocking capture stop, the protocol exit and
     * the local release then run on IO inside one monotonic budget.
     */
    fun requestExit(trigger: ExitTrigger) {
        if (closed) return
        val captured: Ts3SessionClient?
        val handshaking: Boolean
        val deadlineMs: Long
        val firstExit: Boolean
        val exitToken: Long
        synchronized(exitLock) {
            exitToken = ownership.beginExit()
            captured = session
            session = null
            if (captured != null) exitSession = captured
            handshaking = host.mutableState.value.status.phase in interruptibleConnectionPhases
            deadlineMs = clockMs() + EXIT_BUDGET_MS
            // A second request for the same session joins the running exit instead
            // of sending a second leave command.
            firstExit = exitToken != activeExitToken || exitJob == null
        }
        if (!firstExit) return
        host.sessionGeneration.invalidate()
        activeListener = null
        connectionJob?.cancel()
        reconnectEngine.cancel()
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
        // Best effort and non-blocking: detaching the voice source stops the old
        // socket's microphone timer, so no further frame can be sent while the
        // exit is still being prepared. A failure here is not fatal because the
        // socket is closed unconditionally below.
        runCatching { captured?.setVoiceSource(null) }
        host.mutableState.update { current ->
            current
                .withStatus(ConnectionStatus(ConnectionPhase.DISCONNECTING))
                .copy(isTransmitting = false, snapshot = SessionSnapshot.Empty)
        }
        host.updateNotification()
        val job =
            synchronized(exitLock) {
                val previous = exitJob
                host.serviceScope.launch(start = CoroutineStart.LAZY) {
                    previous?.join()
                    exitRunner.run(trigger, captured, handshaking, deadlineMs, exitToken) {
                        if (exitSession === captured) exitSession = null
                    }
                }.also {
                    exitJob = it
                    activeExitToken = exitToken
                    exitJobs += it
                }
            }
        job.invokeOnCompletion { synchronized(exitLock) { exitJobs.remove(job) } }
        job.start()
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
        closed = true
        ownership.invalidate()
        host.sessionGeneration.invalidate()
        activeListener = null
        connectionJob?.cancel()
        reconnectEngine.cancel()
        stableConnectionJob?.cancel()
        connectedWatchJob?.cancel()
        livenessJob?.cancel()
        synchronized(exitLock) { exitJobs.toList() }.forEach { it.cancel() }
        // Destruction releases local resources only: the session an exit captured is
        // closed here instead of starting a new network exit on a scope that is
        // about to be cancelled, and nothing waits for the server.
        val capturedExit = synchronized(exitLock) { exitSession.also { exitSession = null } }
        runCatching { capturedExit?.close() }
        val current = synchronized(exitLock) { session.also { session = null } }
        runCatching { current?.close() }
    }

    override suspend fun performAttempt(epoch: Long): AttemptResult {
        val config = desiredConfig ?: return AttemptResult.Stale
        return performConnectionAttempt(config, epoch, reconnecting = true)
    }

    /** Stop a refused handshake immediately, even if the blocking call ignores interruption. */
    private fun stopRefusedAttempt(epoch: Long) {
        val captured =
            synchronized(exitLock) {
                if (!isEpochActive(epoch)) return
                activeListener?.takeIf { it.epoch == epoch }?.let { listenerRef.compareAndSet(it, null) }
                val previous = session.also { session = null }
                previous to connectionJob
            }
        // No mutex acquisition or cancellation join before the socket is closed.
        runCatching { captured.first?.close() }
        captured.second?.cancel()
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
                val identity = identityMaterial ?: host.createIdentity()
                if (!isEpochActive(epoch)) return@withLock AttemptResult.Stale
                identityMaterial = identity
                host.mutableState.update { it.copy(identityReady = true) }

                host.applySelectedPlaybackMuted()
                host.resetParticipantGains()
                host.startPlayback()

                val newSession = sessionFactory()
                val listener = SessionListener(epoch, reconnecting)
                val accepted =
                    synchronized(exitLock) {
                        if (!isEpochActive(epoch) || refusedEpoch == epoch) {
                            false
                        } else {
                            activeListener = listener
                            session = newSession
                            true
                        }
                    }
                if (!accepted) {
                    newSession.close()
                    return@withLock AttemptResult.Stale
                }
                host.attachMicrophoneTo(newSession)
                runInterruptible { newSession.connect(config, identity, listener) }
                if (!isListenerActive(listener)) {
                    newSession.close()
                    if (session === newSession) session = null
                    return@withLock if (refusedEpoch == epoch) {
                        AttemptResult.Stale
                    } else if (isEpochActive(epoch)) {
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
                    listenerRef.compareAndSet(listener, null)
                    newSession.close()
                    if (session === newSession) session = null
                    AttemptResult.Failed(status, status.retryable && reconnecting)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val failedSession = session
                activeListener = null
                failedSession?.close()
                if (session === failedSession) session = null
                if (!isEpochActive(epoch)) return@withLock AttemptResult.Stale
                if (refusedEpoch == epoch) return@withLock AttemptResult.Stale

                val status =
                    ConnectionStatus(
                        ConnectionPhase.ERROR,
                        host.conciseMessage(error),
                        retryable = error.isRetryableConnectionFailure(),
                        category = error.connectionFailureCategory(),
                    )
                val message = failureMessageFor(status, reconnecting)
                host.mutableState.update { current ->
                    if (reconnecting && status.retryable) {
                        current.withStatus(
                            ConnectionStatus(
                                ConnectionPhase.RECONNECTING,
                                status.detail,
                                retryable = status.retryable,
                                category = status.category,
                            ),
                            message,
                        )
                    } else {
                        current.withStatus(status, message)
                    }.copy(snapshot = SessionSnapshot.Empty)
                }
                host.diagnosticsRecorder.recordConnectionFailure(status.retryable)
                host.onConnectionFailure(status)
                host.refreshDiagnostics()
                AttemptResult.Failed(status, status.retryable && reconnecting)
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
                    if (!reconnecting) withCurrentListener { host.mutableState.update { it.withStatus(status) } }
                }

                ConnectionPhase.CONNECTED -> {
                    connected = true
                    onSessionConnected(this)
                }

                ConnectionPhase.DISCONNECTED,
                ConnectionPhase.ERROR,
                -> {
                    when {
                        status.category == ConnectionFailureCategory.CLONES_LIMIT -> onConnectionRefused(this, status)

                        connected -> onEstablishedSessionEnded(this, status)

                        else -> {
                            withCurrentListener {
                                host.mutableState.update { current ->
                                    val next = current.withStatus(status)
                                    next.copy(snapshot = SessionSnapshot.Empty, isTransmitting = false)
                                }
                            }
                        }
                    }
                }

                ConnectionPhase.DISCONNECTING -> {
                    withCurrentListener {
                        host.mutableState.update { it.withStatus(status) }
                        host.updateNotification()
                    }
                }

                ConnectionPhase.RECONNECTING -> Unit
            }
        }

        override fun onSnapshotChanged(snapshot: SessionSnapshot) {
            if (!isListenerActive(this)) return
            withCurrentListener {
                host.mutableState.update { it.copy(snapshot = snapshot) }
                host.applyParticipantAudioSettings(snapshot)
                if (connected) channelRestore.rememberSnapshot(snapshot.currentChannelId)
                host.updateNotification()
            }
        }

        override fun onVoiceFrame(frame: VoiceFrame) {
            if (!isListenerActive(this) || !connected) {
                host.diagnosticsRecorder.recordVoiceFrameDropped()
                return
            }
            withCurrentListener {
                host.diagnosticsRecorder.recordVoiceFrameReceived()
                host.submitVoiceFrame(frame)
            }
        }

        /** Registration and callback mutation share one short atomic epoch boundary. */
        private fun withCurrentListener(action: () -> Unit) {
            host.sessionGeneration.withActive(epoch) {
                if (activeListener === this) action()
            }
        }
    }

    private fun onSessionConnected(listener: SessionListener) {
        if (!isListenerActive(listener)) return
        host.sessionGeneration.withActive(listener.epoch) {
            if (activeListener !== listener) return@withActive
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

    private fun onConnectionRefused(
        listener: SessionListener,
        status: ConnectionStatus,
    ) {
        val epoch = listener.epoch
        if (!isEpochActive(epoch) || !listenerRef.compareAndSet(listener, null)) return
        refusedEpoch = epoch
        reconnectEngine.cancel()
        val terminal = status.copy(phase = ConnectionPhase.ERROR, retryable = false)
        host.sessionGeneration.withActive(epoch) {
            host.mutableState.update { current ->
                current.withStatus(terminal, UserMessage.ConnectionLimitWait)
                    .copy(snapshot = SessionSnapshot.Empty, isTransmitting = false)
            }
            host.diagnosticsRecorder.recordConnectionFailure(false)
            host.onConnectionFailure(terminal)
        }
        host.serviceScope.launch {
            stopRefusedAttempt(epoch)
            finishTerminalFailure(terminal, epoch)
        }
    }

    /**
     * The app message for a failed attempt. A clone refusal keeps its own wording
     * whether it happened on the first connect or during network recovery; every
     * other reconnect failure keeps the existing generic text.
     */
    private fun failureMessageFor(
        status: ConnectionStatus,
        reconnecting: Boolean,
    ): UserMessage? =
        when {
            status.category == ConnectionFailureCategory.CLONES_LIMIT -> UserMessage.ConnectionLimitWait
            reconnecting -> UserMessage.ReconnectFailed(status.detail?.takeIf(String::isNotBlank))
            else -> null
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
            // New attempts acquire this same mutex before starting shared audio.
            host.stopMicrophoneCapture()
            runInterruptible {
                host.stopPlayback()
                host.stopAudioRouting()
            }
            val updated =
                host.sessionGeneration.withActive(epoch) {
                    host.mutableState.update { current ->
                        current.withStatus(status.copy(retryable = false), failureMessageFor(status, reconnecting = false)).copy(
                            snapshot = SessionSnapshot.Empty,
                            isTransmitting = false,
                            switchingChannelId = null,
                        )
                    }
                }
            if (!updated) return@withLock
            host.refreshDiagnostics()
            host.finishTerminalOnMainThread(epoch)
        }
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

    private fun isListenerActive(listener: SessionListener): Boolean =
        activeListener === listener && isEpochActive(listener.epoch) && refusedEpoch != listener.epoch

    private fun isEpochActive(epoch: Long): Boolean = host.sessionGeneration.isActive(epoch)

    private companion object {
        const val STABLE_CONNECTION_MS = 30_000L
        const val CONNECTED_WATCH_INTERVAL_MS = 1_000L
        const val WATCH_EXECUTION_GAP_THRESHOLD_MS = 10_000L
        const val MILLIS_PER_SECOND = 1_000L

        /** Total budget for one exit: capture stop, mutex wait, protocol exit and release. */
        const val EXIT_BUDGET_MS = 2_000L

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
