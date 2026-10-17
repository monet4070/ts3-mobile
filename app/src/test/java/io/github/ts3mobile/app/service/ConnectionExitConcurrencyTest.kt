package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.DisconnectOutcome
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    @Before
    fun setUp() {
        host = Host()
        coordinator =
            ConnectionCoordinator(
                host,
                clockMs = { System.nanoTime() / NANOS_PER_MILLISECOND },
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
    fun theExitBudgetCoversASlowCaptureStopAndStillReturnsInsideIt() {
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
            "capture stop was not spent from the budget: ${session.boundedDisconnectBudgetMs}ms",
            session.boundedDisconnectBudgetMs <= EXIT_BUDGET_MS - CAPTURE_STOP_MS + SLACK_MS,
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

        val diagnosticCodes = CopyOnWriteArrayList<Pair<DiagnosticEventKind, Long>>()

        override fun recordDiagnostic(
            kind: DiagnosticEventKind,
            code: Long,
        ) {
            diagnosticCodes += kind to code
        }

        var microphoneStopAction: () -> Unit = {}
        var identityAction: () -> Unit = {}

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

        override fun startAudioRouting() = Unit

        override fun stopAudioRouting() = Unit

        override fun applySelectedPlaybackMuted() = Unit

        override fun startPlayback() = Unit

        override fun stopPlayback() {
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
