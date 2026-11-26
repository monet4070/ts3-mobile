package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.identity.IdentityUseGate
import io.github.ts3mobile.app.identity.IdentityUseState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.DisconnectOutcome
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exit/connect races on real worker threads.
 *
 * Virtual-time tests cannot model a handshake that is blocked inside the native
 * connect, or a protocol exit that is still waiting for its confirmation, so these
 * cases run on a real pool with latches and bounded waits. Nothing here opens a
 * socket, talks to a server or touches Android.
 */
class ConnectionExitConcurrencyTest {
    private lateinit var host: Host
    private lateinit var coordinator: ConnectionCoordinator
    private lateinit var identityGate: IdentityUseGate

    @Before
    fun setUp() {
        host = Host()
        identityGate = IdentityUseGate()
        coordinator =
            ConnectionCoordinator(
                host,
                clockMs = { System.nanoTime() / NANOS_PER_MILLISECOND },
                identityGate = identityGate,
                sessionFactory = { host.nextSession },
            )
        host.coordinator = coordinator
    }

    @After
    fun tearDown() {
        coordinator.close()
        host.close()
    }

    @Test
    fun anExitDuringABlockedHandshakeClosesTheSocketWithoutWaitingForConnected() {
        val connectEntered = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)
        val session = ScriptedSession(connectEntered = connectEntered, connectGate = releaseConnect)
        host.nextSession = session

        coordinator.beginConnection(CONFIG)
        assertTrue(connectEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertEquals(ConnectionPhase.CONNECTING, host.mutableState.value.status.phase)

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)

        // The exit never waits for CONNECTED: it closes the captured socket to wake
        // the blocked connect, and no leave command is sent for a session the server
        // may never have accepted.
        assertTrue(awaitUntil { session.closeCalls.get() > 0 })
        assertEquals(0, session.boundedDisconnectCalls.get())

        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.DISCONNECTED })
        assertFalse("a cancelled handshake published CONNECTED", host.phases.contains(ConnectionPhase.CONNECTED))
    }

    @Test
    fun aConnectQueuedBehindARunningExitKeepsItsOwnConfigAndSession() {
        val exitEntered = CountDownLatch(1)
        val releaseExit = CountDownLatch(1)
        val first = ScriptedSession(exitEntered = exitEntered, exitGate = releaseExit)
        host.nextSession = first
        coordinator.beginConnection(CONFIG)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        assertTrue(exitEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        // The user reopens and connects while the exit is still waiting for its
        // confirmation: the new request is kept, owns the state and is not torn down
        // by the exit that it replaced.
        val second = ScriptedSession()
        host.nextSession = second
        coordinator.beginConnection(SECOND_CONFIG)
        assertEquals(SECOND_LABEL, host.mutableState.value.serverLabel)

        releaseExit.countDown()
        assertTrue(awaitUntil { second.connectCalls.get() == 1 })
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })

        assertFalse("the replaced exit stopped the service", host.stopSelfRequested)
        assertEquals(0, second.closeCalls.get())
        assertEquals(SECOND_CONFIG, second.connectedConfig)
        assertEquals(SECOND_LABEL, host.mutableState.value.serverLabel)
    }

    @Test
    fun aSlowCaptureStopDoesNotDelayTheProtocolExitAndStillReturnsInsideTheBudget() {
        val session = ScriptedSession()
        host.nextSession = session
        coordinator.beginConnection(CONFIG)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })

        host.microphoneStopDelayMs = CAPTURE_STOP_MS
        val startedAt = System.nanoTime()
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.DISCONNECTED })
        val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertTrue(
            "capture stop consumed the protocol budget: ${session.boundedDisconnectBudgetMs}ms",
            session.boundedDisconnectBudgetMs > EXIT_BUDGET_MS - CAPTURE_STOP_MS,
        )
        assertTrue("exit took ${elapsedMs}ms", elapsedMs <= EXIT_BUDGET_MS + SLACK_MS)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.DISCONNECTED })
    }

    @Test
    fun captureAndPlaybackReclamationShareTheDeadlineWithoutAddingTheirWaits() {
        host.nextSession = ScriptedSession()
        coordinator.beginConnection(CONFIG)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
        host.microphoneStopDelayMs = 600
        host.playbackStopDelayMs = 1_600
        val started = System.nanoTime()

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.DISCONNECTED })

        val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
        assertTrue("audio waits exceeded the total exit budget: $elapsed ms", elapsed <= EXIT_BUDGET_MS + 100)
    }

    @Test
    fun aNewConnectWaitsEvenWhenTheOldExitHasNotAcquiredTheSessionMutex() {
        host.nextSession = ScriptedSession()
        coordinator.beginConnection(CONFIG)
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val firstStop = AtomicBoolean(true)
        host.microphoneStopAction = {
            if (firstStop.compareAndSet(true, false)) {
                stopEntered.countDown()
                releaseStop.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
        }
        val newConnectEntered = CountDownLatch(1)
        val second = ScriptedSession(connectEntered = newConnectEntered)
        try {
            coordinator.requestExit(ExitTrigger.TASK_REMOVED)
            assertTrue(stopEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            host.nextSession = second
            coordinator.beginConnection(SECOND_CONFIG)
            assertFalse("new connection overtook the old exit", newConnectEntered.await(250, TimeUnit.MILLISECONDS))
        } finally {
            releaseStop.countDown()
        }
        assertTrue(newConnectEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
        assertEquals(SECOND_CONFIG, second.connectedConfig)
        assertFalse(host.stopSelfRequested)
    }

    @Test
    fun removalDuringIdentityLoadingCannotStartASocketAfterIdentityReturns() {
        val identityEntered = CountDownLatch(1)
        val releaseIdentity = CountDownLatch(1)
        host.identityAction = {
            identityEntered.countDown()
            releaseIdentity.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }
        val session = ScriptedSession()
        host.nextSession = session
        coordinator.beginConnection(CONFIG)
        assertTrue(identityEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        releaseIdentity.countDown()

        assertTrue(awaitUntil { host.stopSelfRequested })
        assertEquals("cancelled identity work opened a socket", 0, session.connectCalls.get())
    }

    @Test
    fun concurrentExitDuringTerminalFailureRetirementNeverThrowsAndSettlesIdentityGate() {
        repeat(25) {
            val failingSession =
                ScriptedSession(
                    connectError = IllegalStateException("terminal failure"),
                )
            host.nextSession = failingSession
            val failureFinishing = CompletableDeferred<Unit>()
            host.finishTerminalAction = { failureFinishing.complete(Unit) }
            assertTrue(coordinator.beginConnection(CONFIG))
            runBlocking { failureFinishing.await() }

            // Race requestExit directly against finishTerminalFailure retiring identityLease.
            coordinator.requestExit(ExitTrigger.USER_DISCONNECT)
            assertTrue(awaitUntil { identityGate.state.value == IdentityUseState.IDLE })
            host.finishTerminalAction = {}
        }
    }

    @Test
    fun connectAndExitPreserveUserMicrophoneSettingsAndAudioDiagnosticsStatus() {
        val initialSettings =
            MicrophoneSettings(
                volumePercent = 165,
                noiseSuppression = false,
                echoCancellation = false,
                gateThresholdDb = -28,
            )
        val initialAudioDiag = AudioDiagnosticStatus(savedSamples = 42, sessions = 3, marks = 2)
        host.mutableState.value =
            TeamSpeakServiceState(
                microphoneMode = MicrophoneMode.VOLUME_GATE,
                microphoneSettings = initialSettings,
                audioDiagnostics = initialAudioDiag,
            )
        host.nextSession = ScriptedSession()
        assertTrue(coordinator.beginConnection(CONFIG))
        assertEquals(initialSettings, host.mutableState.value.microphoneSettings)
        assertEquals(MicrophoneMode.VOLUME_GATE, host.mutableState.value.microphoneMode)
        assertEquals(initialAudioDiag, host.mutableState.value.audioDiagnostics)

        assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
        coordinator.requestExit(ExitTrigger.USER_DISCONNECT)
        assertTrue(awaitUntil { identityGate.state.value == IdentityUseState.IDLE })
        assertEquals(initialSettings, host.mutableState.value.microphoneSettings)
        assertEquals(MicrophoneMode.VOLUME_GATE, host.mutableState.value.microphoneMode)
        assertEquals(initialAudioDiag, host.mutableState.value.audioDiagnostics)
    }

    @Test
    fun connectAfterDegradedExitWithStuckMutexFailsWithinBudgetWithoutTouchingAudioAndSucceedsAfterUnlock() {
        val boundedCoordinator =
            ConnectionCoordinator(
                host,
                clockMs = { System.nanoTime() / NANOS_PER_MILLISECOND },
                identityGate = identityGate,
                sessionFactory = { host.nextSession },
                sessionMutexTimeoutMs = 300L,
            )
        try {
            boundedCoordinator.beginConnection(CONFIG)
            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })

            runBlocking { host.sessionMutex.lock() }
            try {
                boundedCoordinator.requestDisconnect()
                assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.DISCONNECTED })

                val captureStopsBeforeConnect = host.captureStopCalls.get()
                val routingStartsBeforeConnect = host.routingStarts.get()
                val playbackStartsBeforeConnect = host.playbackStarts.get()
                val connectsBeforeRetry = host.nextSession.connectCalls.get()

                assertTrue(boundedCoordinator.beginConnection(SECOND_CONFIG))
                assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.ERROR })
                assertEquals(UserMessage.PreviousSessionBusy, host.mutableState.value.statusMessage)
                assertFalse(host.mutableState.value.status.retryable)
                assertTrue(awaitUntil { identityGate.state.value == IdentityUseState.IDLE })
                assertEquals(captureStopsBeforeConnect, host.captureStopCalls.get())
                assertEquals(routingStartsBeforeConnect, host.routingStarts.get())
                assertEquals(playbackStartsBeforeConnect, host.playbackStarts.get())
                assertEquals(connectsBeforeRetry, host.nextSession.connectCalls.get())
            } finally {
                host.sessionMutex.unlock()
            }

            assertTrue(awaitUntil { host.playbackStopCalls.get() >= 1 && host.routingStopCalls.get() >= 1 })
            host.nextSession = ScriptedSession()
            assertTrue(boundedCoordinator.beginConnection(SECOND_CONFIG))
            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
            assertEquals(1, host.nextSession.connectCalls.get())
        } finally {
            boundedCoordinator.close()
        }
    }

    @Test
    fun nextConnectFailsBoundedlyWhenPreviousExitHangsInAudioCleanupWithoutStartingAudio() {
        val boundedCoordinator =
            ConnectionCoordinator(
                host,
                clockMs = { System.nanoTime() / NANOS_PER_MILLISECOND },
                identityGate = identityGate,
                sessionMutexTimeoutMs = 300L,
                sessionFactory = { host.nextSession },
            )
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        try {
            boundedCoordinator.beginConnection(CONFIG)
            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })

            val hungOnce = AtomicBoolean(true)
            host.microphoneStopAction = {
                if (hungOnce.compareAndSet(true, false)) {
                    stopEntered.countDown()
                    releaseStop.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
            boundedCoordinator.requestDisconnect()
            assertTrue(stopEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

            val routingStartsBeforeConnect = host.routingStarts.get()
            val playbackStartsBeforeConnect = host.playbackStarts.get()
            val connectsBeforeRetry = host.nextSession.connectCalls.get()

            assertTrue(boundedCoordinator.beginConnection(SECOND_CONFIG))
            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.ERROR })
            assertEquals(UserMessage.PreviousSessionBusy, host.mutableState.value.statusMessage)
            assertFalse(host.mutableState.value.status.retryable)
            assertEquals(routingStartsBeforeConnect, host.routingStarts.get())
            assertEquals(playbackStartsBeforeConnect, host.playbackStarts.get())
            assertEquals(connectsBeforeRetry, host.nextSession.connectCalls.get())

            releaseStop.countDown()
            assertTrue(awaitUntil { !host.sessionMutex.isLocked && identityGate.state.value == IdentityUseState.IDLE })
            host.nextSession = ScriptedSession()
            assertTrue(boundedCoordinator.beginConnection(SECOND_CONFIG))
            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
            assertEquals(1, host.nextSession.connectCalls.get())
        } finally {
            releaseStop.countDown()
            boundedCoordinator.close()
        }
    }

    @Test
    fun staleBusyFailureCannotCloseReplacementSessionsSocket() {
        val replacementSession = ScriptedSession()
        val createdSessions = CopyOnWriteArrayList<ScriptedSession>()
        val raceCoordinator =
            ConnectionCoordinator(
                host,
                clockMs = { System.nanoTime() / NANOS_PER_MILLISECOND },
                identityGate = identityGate,
                sessionMutexTimeoutMs = 200L,
                sessionFactory = { replacementSession.also { createdSessions += it } },
            )
        val hookEntered = CountDownLatch(1)
        val resumeHook = CountDownLatch(1)
        val testLockOwner = Any()
        var testHoldsMutex = false
        try {
            runBlocking { host.sessionMutex.lock(testLockOwner) }
            testHoldsMutex = true
            try {
                host.connectionFailureAction = {
                    hookEntered.countDown()
                    resumeHook.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
                assertTrue(raceCoordinator.beginConnection(CONFIG))
                assertTrue(hookEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
                host.connectionFailureAction = {}
                testHoldsMutex = false
                host.sessionMutex.unlock(testLockOwner)

                assertTrue(raceCoordinator.beginConnection(SECOND_CONFIG))
                assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
            } finally {
                resumeHook.countDown()
                if (testHoldsMutex) {
                    testHoldsMutex = false
                    host.sessionMutex.unlock(testLockOwner)
                }
            }

            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
            assertEquals(1, createdSessions.size)
            assertEquals(0, replacementSession.closeCalls.get())
            assertEquals(SECOND_CONFIG, replacementSession.connectedConfig)
        } finally {
            raceCoordinator.close()
        }
    }

    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + WAIT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    private class ScriptedSession(
        private val connectEntered: CountDownLatch? = null,
        private val connectGate: CountDownLatch? = null,
        private val exitEntered: CountDownLatch? = null,
        private val exitGate: CountDownLatch? = null,
        private val connectError: Throwable? = null,
    ) : Ts3SessionClient {
        val connectCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val boundedDisconnectCalls = AtomicInteger()

        @Volatile
        var closed = false

        @Volatile
        var boundedDisconnectBudgetMs = 0L

        @Volatile
        var connectedConfig: ServerConfig? = null

        @Volatile
        var boundedDisconnectOutcome = DisconnectOutcome.SERVER_CONFIRMED

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) {
            connectCalls.incrementAndGet()
            connectedConfig = config
            connectEntered?.countDown()
            connectGate?.await(WAIT_SECONDS, TimeUnit.SECONDS)
            connectError?.let { throw it }
            if (!closed) listener.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
        }

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit

        override fun disconnect(reason: String) = Unit

        override suspend fun disconnectBounded(
            timeoutMs: Long,
            reason: String,
            onRequestSent: () -> Unit,
        ): DisconnectOutcome {
            boundedDisconnectCalls.incrementAndGet()
            boundedDisconnectBudgetMs = timeoutMs
            onRequestSent()
            exitEntered?.countDown()
            exitGate?.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return boundedDisconnectOutcome
        }

        override fun close() {
            closeCalls.incrementAndGet()
            closed = true
            connectGate?.countDown()
        }
    }

    private class Host : ConnectionCoordinatorHost {
        private val executor = Executors.newFixedThreadPool(EXECUTOR_THREADS)
        override val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        override val sessionMutex = Mutex()
        override val sessionGeneration = SessionGeneration()
        override val networkAvailable = MutableStateFlow(true)
        override val mutableState = MutableStateFlow(TeamSpeakServiceState())
        override val reconnectPolicy = ReconnectPolicy()
        override val diagnosticsRecorder = DiagnosticsRecorder()
        val phases = CopyOnWriteArrayList<ConnectionPhase>()

        @Volatile
        var nextSession: ScriptedSession = ScriptedSession()

        @Volatile
        var stopSelfRequested = false

        @Volatile
        var microphoneStopDelayMs = 0L
        var playbackStopDelayMs = 0L
        val captureStopCalls = AtomicInteger()
        val routingStarts = AtomicInteger()
        val routingStopCalls = AtomicInteger()
        val playbackStarts = AtomicInteger()
        val playbackStopCalls = AtomicInteger()

        val diagnosticCodes = CopyOnWriteArrayList<Pair<DiagnosticEventKind, Long>>()

        override fun recordDiagnostic(
            kind: DiagnosticEventKind,
            code: Long,
        ) {
            diagnosticCodes += kind to code
        }

        var microphoneStopAction: () -> Unit = {}
        var identityAction: () -> Unit = {}
        var finishTerminalAction: () -> Unit = {}
        var connectionFailureAction: () -> Unit = {}

        override fun onConnectionFailure(status: ConnectionStatus) {
            connectionFailureAction()
        }

        override fun finishTerminalOnMainThread(epoch: Long) {
            finishTerminalAction()
            super.finishTerminalOnMainThread(epoch)
        }

        @Volatile
        var coordinator: ConnectionCoordinator? = null

        init {
            serviceScope.launch {
                mutableState.collect { phases += it.status.phase }
            }
        }

        override fun conciseMessage(error: Throwable): String = error::class.java.simpleName

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() {
            captureStopCalls.incrementAndGet()
            runInterruptible {
                microphoneStopAction()
                val delay = microphoneStopDelayMs
                if (delay > 0L) Thread.sleep(delay)
            }
        }

        override suspend fun createIdentity(): String {
            identityAction()
            return "identity"
        }

        override fun startAudioRouting() {
            routingStarts.incrementAndGet()
        }

        override fun stopAudioRouting() {
            routingStopCalls.incrementAndGet()
        }

        override fun applySelectedPlaybackMuted() = Unit

        override suspend fun startPlayback() {
            playbackStarts.incrementAndGet()
        }

        override fun stopPlayback() {
            playbackStopCalls.incrementAndGet()
            if (playbackStopDelayMs > 0) Thread.sleep(playbackStopDelayMs)
        }

        override fun clearPlaybackMuted() = Unit

        override fun attachMicrophoneTo(session: Ts3SessionClient) = Unit

        override fun stopMicrophoneImmediately() = Unit

        override fun reconcileMicrophone() = Unit

        override fun applyParticipantAudioSettings(snapshot: SessionSnapshot) = Unit

        override fun submitVoiceFrame(frame: VoiceFrame) = Unit

        override fun removeForegroundNotification() = Unit

        override fun requestStopSelf() {
            stopSelfRequested = true
        }

        override fun finishExitOnMainThread(exitToken: Long) {
            // Same token check the service performs on the main thread.
            if (coordinator?.isLatestExit(exitToken) == true) stopSelfRequested = true
        }

        fun close() {
            serviceScope.cancel()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 5L
        const val POLL_MILLIS = 5L
        const val EXECUTOR_THREADS = 4
        const val CAPTURE_STOP_MS = 600L
        const val SLACK_MS = 600L
        const val EXIT_BUDGET_MS = 2_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L

        val CONFIG =
            ServerConfig(
                host = "example.invalid",
                port = 9987,
                nickname = "tester",
            )

        val SECOND_CONFIG =
            ServerConfig(
                host = "second.invalid",
                port = 9988,
                nickname = "tester",
            )

        val SECOND_LABEL = "${SECOND_CONFIG.host}:${SECOND_CONFIG.port}"
    }
}
