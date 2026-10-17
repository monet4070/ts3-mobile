package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Virtual-time coverage of the bounded clone-limit window: the backoff ladder, the
 * fixed deadline, offline waiting and every way the window can end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClonesRecoveryEngineTest {
    @Test
    fun audioPreparationKeepsTheRecoveringPhaseAndCancelEntryVisible() {
        delegate.audioDelayMs = 5_000
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())
        testScope.advanceTimeBy(1_000)
        assertEquals(ConnectionPhase.RECONNECTING, host.mutableState.value.status.phase)
        assertEquals(UserMessage.ClonesLimitRetrying, host.mutableState.value.statusMessage)
        assertTrue(delegate.attemptTimes.isEmpty())
        engine.cancel()
    }

    @Test
    fun replacedRequestCannotClaimRecoverySuccessOrTerminalFailure() {
        val old = host.sessionGeneration.beginSession()
        engine.noteRejection(old)
        host.sessionGeneration.beginSession()
        assertFalse(engine.acceptConnected(old))
        assertFalse(engine.noteTerminalFailure(old, ConnectionStatus(ConnectionPhase.ERROR)))
        engine.cancel()
        assertEquals(DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.CANCELLED.code, host.events.last())
    }

    @Test
    fun cancellingBeforeTheRetryJobStartsStillPairsTheWindowEvents() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.cancel()
        testScope.advanceTimeBy(61_000)
        assertEquals(
            listOf(
                DiagnosticEventKind.CLONES_RECOVERY_STARTED to 0L,
                DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.CANCELLED.code,
            ),
            host.events,
        )
        assertTrue(delegate.attemptTimes.isEmpty())
    }

    @Test
    fun networkReturningAfterTheOfflineDeadlineCannotReopenTheWindow() {
        host.networkAvailable.value = false
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())
        testScope.advanceTimeBy(60_500)
        host.networkAvailable.value = true
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())
        testScope.advanceTimeBy(10_000)
        assertTrue(delegate.attemptTimes.isEmpty())
        assertEquals(1, host.events.count { it.first == DiagnosticEventKind.CLONES_RECOVERY_STARTED })
        assertEquals(1, host.events.count { it.first == DiagnosticEventKind.CLONES_RECOVERY_ENDED })
        assertEquals(UserMessage.ClonesLimitExpired, host.mutableState.value.statusMessage)
    }

    @Test
    fun unexpectedAttemptFailureHandsOffTerminalCleanup() {
        delegate.attemptFailure = IllegalStateException("fixture failure")
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())
        testScope.advanceTimeBy(2_000)
        assertFalse(engine.isActive)
        assertEquals(1, delegate.terminalFailures.size)
        assertEquals(DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.FAILED.code, host.events.last())
    }

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private lateinit var host: Host
    private lateinit var delegate: FakeDelegate
    private lateinit var engine: ClonesRecoveryEngine

    @Before
    fun setUp() {
        host = Host(CoroutineScope(SupervisorJob() + dispatcher))
        delegate = FakeDelegate { dispatcher.scheduler.currentTime }
        engine =
            ClonesRecoveryEngine(
                host = host,
                delegate = delegate,
                clockMs = { dispatcher.scheduler.currentTime },
            )
    }

    @Test
    fun theWindowOnlyOpensForTheEpochWhoseRefusalWasObserved() {
        val epoch = host.sessionGeneration.beginSession()

        assertFalse(engine.isRejectedFor(epoch))

        engine.noteRejection(epoch)

        assertTrue(engine.isRejectedFor(epoch))
        assertFalse(engine.isRejectedFor(epoch + 1))
    }

    @Test
    fun repeatedRefusalsNeverMoveTheDeadline() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(50_000L)
        // The window keeps seeing refusals from its own attempts.
        engine.noteRejection(epoch)
        testScope.advanceTimeBy(9_000L)
        assertTrue("window closed before its deadline", engine.isActive)

        testScope.advanceTimeBy(2_000L)

        assertFalse(engine.isActive)
        assertEquals(
            DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.EXPIRED.code,
            host.events.last(),
        )
    }

    @Test
    fun retriesBackOffOneTwoFourThenEightSeconds() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(40_000L)

        val deltas = delegate.attemptTimes.zipWithNext { first, second -> second - first }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 8_000L, 8_000L, 8_000L), deltas)
        assertEquals(1_000L, delegate.attemptTimes.first())
    }

    @Test
    fun anAttemptNeverGetsMoreTimeThanTheWindowHasLeft() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(60_000L)

        assertTrue(delegate.attemptBudgets.isNotEmpty())
        assertTrue(delegate.attemptBudgets.all { it in 1..60_000L })
        assertTrue("attempts continued past the window", delegate.attemptTimes.all { it <= 60_000L })
    }

    @Test
    fun anOfflineDeviceWaitsWithoutAttemptingAndStillExpires() {
        host.networkAvailable.value = false
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(10_000L)

        assertEquals(emptyList<Long>(), delegate.attemptTimes)
        assertEquals(UserMessage.ClonesLimitWaitingForNetwork, host.mutableState.value.statusMessage)

        host.networkAvailable.value = true
        testScope.advanceTimeBy(50_500L)

        assertTrue("no attempt ran after the network returned", delegate.attemptTimes.isNotEmpty())
        assertFalse("the window did not expire at its deadline", engine.isActive)
        assertEquals(
            DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.EXPIRED.code,
            host.events.last(),
        )
    }

    @Test
    fun anExpiredWindowPublishesTheActionableStateAndRemovesTheNotification() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(60_500L)

        assertEquals(ConnectionPhase.ERROR, host.mutableState.value.status.phase)
        assertEquals(UserMessage.ClonesLimitExpired, host.mutableState.value.statusMessage)
        assertFalse(host.mutableState.value.status.retryable)
        assertEquals(1, host.notificationRemovals)
    }

    @Test
    fun aTerminalFailureEndsTheWindowImmediately() {
        delegate.defaultOutcome =
            AttemptResult.Failed(
                ConnectionStatus(ConnectionPhase.ERROR, "invalid password", retryable = false),
                retryable = false,
            )
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(10_000L)

        assertFalse(engine.isActive)
        assertEquals(1, delegate.terminalFailures.size)
        assertEquals(
            DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.TERMINAL.code,
            host.events.last(),
        )
    }

    @Test
    fun aTransientNetworkFailureInsideTheWindowKeepsRetrying() {
        delegate.outcomes +=
            AttemptResult.Failed(
                ConnectionStatus(ConnectionPhase.ERROR, "offline", retryable = true),
                retryable = true,
            )
        delegate.outcomes += AttemptResult.Success
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())

        testScope.advanceTimeBy(10_000L)

        assertFalse(engine.isActive)
        assertEquals(2, delegate.attemptTimes.size)
        assertEquals(
            DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.CONNECTED.code,
            host.events.last(),
        )
    }

    @Test
    fun cancellingTheWindowRecordsACancelledOutcome() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())
        testScope.advanceTimeBy(2_000L)

        engine.cancel()
        testScope.advanceTimeBy(10_000L)

        assertFalse(engine.isActive)
        assertFalse(engine.isRejectedFor(epoch))
        assertEquals(
            DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.CANCELLED.code,
            host.events.last(),
        )
    }

    @Test
    fun aReplacedRequestEndsTheWindowAsCancelled() {
        val epoch = host.sessionGeneration.beginSession()
        engine.noteRejection(epoch)
        engine.launch(epoch, clonesRefusal())
        testScope.advanceTimeBy(2_000L)

        // A new user request advances the session generation.
        host.sessionGeneration.beginSession()
        testScope.advanceTimeBy(10_000L)

        assertFalse(engine.isActive)
        assertEquals(
            DiagnosticEventKind.CLONES_RECOVERY_ENDED to ClonesRecoveryResult.CANCELLED.code,
            host.events.last(),
        )
    }

    private fun clonesRefusal(): ConnectionStatus =
        ConnectionStatus(
            phase = ConnectionPhase.ERROR,
            detail = "refused",
            retryable = true,
            category = ConnectionFailureCategory.CLONES_LIMIT,
        )

    private class FakeDelegate(
        private val now: () -> Long,
    ) : ClonesRecoveryEngine.Delegate {
        override var desiredConfig: ServerConfig? =
            ServerConfig(host = "example.invalid", nickname = "tester")
        val attemptTimes = mutableListOf<Long>()
        val attemptBudgets = mutableListOf<Long>()
        val outcomes = ArrayDeque<AttemptResult>()
        val terminalFailures = mutableListOf<ConnectionStatus>()
        var attemptFailure: Throwable? = null
        var audioDelayMs = 0L
        var defaultOutcome: AttemptResult =
            AttemptResult.Failed(
                ConnectionStatus(
                    ConnectionPhase.ERROR,
                    "refused",
                    retryable = true,
                    category = ConnectionFailureCategory.CLONES_LIMIT,
                ),
                retryable = true,
            )

        override suspend fun performAttemptWithin(
            epoch: Long,
            remainingMs: Long,
        ): AttemptResult {
            attemptTimes += now()
            attemptBudgets += remainingMs
            attemptFailure?.let { throw it }
            return if (outcomes.isEmpty()) defaultOutcome else outcomes.removeFirst()
        }

        override suspend fun finishTerminalFailure(
            status: ConnectionStatus,
            epoch: Long,
        ) {
            terminalFailures += status
        }

        override suspend fun suspendAudioForRecovery() {
            kotlinx.coroutines.delay(audioDelayMs)
        }
    }

    private class Host(override val serviceScope: CoroutineScope) : ConnectionCoordinatorHost {
        override val sessionMutex = Mutex()
        override val sessionGeneration = SessionGeneration()
        override val networkAvailable = MutableStateFlow(true)
        override val mutableState = MutableStateFlow(TeamSpeakServiceState())
        override val reconnectPolicy = ReconnectPolicy()
        override val diagnosticsRecorder = DiagnosticsRecorder()
        val events = mutableListOf<Pair<DiagnosticEventKind, Long>>()
        var notificationRemovals = 0

        override fun conciseMessage(error: Throwable) = "failure"

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() = Unit

        override suspend fun createIdentity() = "fixture"

        override fun startAudioRouting() = Unit

        override fun stopAudioRouting() = Unit

        override fun applySelectedPlaybackMuted() = Unit

        override fun startPlayback() = Unit

        override fun stopPlayback() = Unit

        override fun clearPlaybackMuted() = Unit

        override fun attachMicrophoneTo(session: Ts3SessionClient) = Unit

        override fun stopMicrophoneImmediately() = Unit

        override fun reconcileMicrophone() = Unit

        override fun applyParticipantAudioSettings(snapshot: SessionSnapshot) = Unit

        override fun submitVoiceFrame(frame: VoiceFrame) = Unit

        override fun removeForegroundNotification() = Unit

        override fun requestStopSelf() = Unit

        override fun recordDiagnostic(
            kind: DiagnosticEventKind,
            code: Long,
        ) {
            events += kind to code
        }

        override fun removeForegroundNotificationOnMainThread(epoch: Long) {
            notificationRemovals++
        }
    }
}
