package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Drives the connection state machine through a fake host with no Android
 * framework classes, mirroring how MicrophoneController is unit-tested.
 * The FakeSession captures the listener the coordinator attaches so tests
 * can deliver protocol callbacks on the coordinator's own terms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {
    private lateinit var host: FakeHost
    private lateinit var coordinator: ConnectionCoordinator

    @Before
    fun setUp() {
        host = FakeHost()
        coordinator =
            ConnectionCoordinator(
                host,
                clockMs = host::readClockMs,
                sessionFactory = { host.session },
            )
        host.latestExitCheck = { coordinator.isLatestExit(it) }
    }

    @After
    fun tearDown() {
        coordinator.close()
        host.scope.cancel()
    }

    @Test
    fun staleListenerCallbacksDoNotMutateStateOrReconnect() {
        coordinator.beginConnection(CONFIG)
        host.advance()
        val captured = host.session.listener ?: error("listener was not attached")
        host.session.emittedStatusOnConnect = null

        // Replace the session epoch, making the captured listener stale.
        coordinator.beginConnection(CONFIG)
        host.advance()

        val statusBefore = host.state.value.status
        captured.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
        captured.onSnapshotChanged(SessionSnapshot.Empty)

        assertEquals(statusBefore, host.state.value.status)
        assertEquals(null, host.state.value.switchingChannelId)
    }

    @Test
    fun requestDisconnectInvalidatesGenerationAndSkipsReconnect() {
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        val connected = host.session.listener ?: error("listener was not attached")
        connected.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
        host.advance()

        coordinator.requestDisconnect()
        host.advance()
        host.runPendingTeardowns()

        assertFalse(host.sessionGeneration.isActive(1L))
        assertTrue(host.stopSelfRequested)
        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
    }

    @Test
    fun networkLossWhileConnectedDrivesRetryableReconnect() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()
        val connected = host.session.listener ?: error("listener was not attached")
        assertTrue(host.state.value.status.phase == ConnectionPhase.CONNECTED)

        host.network.value = false
        coordinator.onNetworkChanged(false)

        assertEquals(ConnectionPhase.RECONNECTING, host.state.value.status.phase)
        assertTrue(host.state.value.status.retryable)

        host.network.value = true
        coordinator.onNetworkChanged(true)
        host.advance()

        assertTrue(host.session.connectCalls >= 2)
    }

    @Test
    fun networkLossSurfacesTheWaitingForNetworkMessageWithoutFormattingIt() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.network.value = false
        coordinator.onNetworkChanged(false)

        assertEquals(UserMessage.WaitingForNetwork, host.state.value.statusMessage)
    }

    @Test
    fun aReconnectMessageDoesNotOutliveTheStatusThatProducedIt() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.network.value = false
        coordinator.onNetworkChanged(false)
        assertEquals(UserMessage.WaitingForNetwork, host.state.value.statusMessage)

        host.network.value = true
        coordinator.onNetworkChanged(true)
        host.advance()

        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertNull(host.state.value.statusMessage)
    }

    @Test
    fun losingTheSessionDuringAChannelSwitchReportsNotConnectedRatherThanAnInternalMessage() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)

        // The launched switch has not run yet on the test dispatcher, so
        // dropping the connection first exercises the in-flight race path.
        coordinator.joinChannel(channelId = 42, password = "")
        host.mutableState.value =
            host.mutableState.value.withStatus(ConnectionStatus(ConnectionPhase.DISCONNECTED))
        host.advance()

        assertEquals(UserMessage.NotConnected, host.state.value.channelError)
        assertNull(host.state.value.switchingChannelId)
    }

    @Test
    fun terminalFailureStopsForegroundAndRequestsStopSelf() {
        host.session.connectAction = { error("boom") }
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.runPendingTeardowns()

        assertEquals(ConnectionPhase.ERROR, host.state.value.status.phase)
        assertTrue(host.stopSelfRequested)
        assertTrue(host.removeForegroundNotificationCalls >= 1)
    }

    @Test
    fun terminalAudioCleanupCannotOverwriteAConnectRegisteredWhileItWasStopping() {
        host.session.connectAction = { error("first attempt failed") }
        host.playbackStopAction = {
            host.playbackStopAction = {}
            host.session.connectAction = {}
            coordinator.beginConnection(CONFIG.copy(port = 9988))
        }
        coordinator.beginConnection(CONFIG)
        host.advance()

        assertFalse("old terminal cleanup stopped a newer request", host.stopSelfRequested)
        assertEquals(ConnectionPhase.CONNECTING, host.state.value.status.phase)
        assertEquals("example.invalid:9988", host.state.value.serverLabel)
    }

    @Test
    fun queuedTerminalTeardownCannotStopANewerConnect() {
        host.session.connectAction = { error("first attempt failed") }
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertFalse(host.stopSelfRequested)

        host.session.connectAction = {}
        coordinator.beginConnection(CONFIG.copy(port = 9988))
        host.runPendingTeardowns()
        host.advance()

        assertFalse(host.stopSelfRequested)
        assertEquals(0, host.removeForegroundNotificationCalls)
        assertEquals(ConnectionPhase.CONNECTING, host.state.value.status.phase)
    }

    @Test
    fun duplicateDefaultNetworkCallbacksDoNotForceReconnect() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        val connectsAfterSuccess = host.session.connectCalls

        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        host.advance()

        assertEquals(connectsAfterSuccess, host.session.connectCalls)
        assertEquals(0, host.session.livenessCalls)
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
    }

    @Test
    fun aPendingNetworkIdentityIsNeverTreatedAsLoss() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()
        val connectsAfterSuccess = host.session.connectCalls

        coordinator.onNetworkChanged(
            DefaultNetworkState(isPending = true, networkKey = "network-2", generation = 2L),
        )
        host.advance()

        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(connectsAfterSuccess, host.session.connectCalls)
        assertEquals(0, host.session.livenessCalls)
    }

    @Test
    fun aConfirmedNetworkSwitchKeepsASilentButAliveSession() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()

        // No voice frames at all: the control round trip, not audio, decides liveness.
        host.session.livenessResult = true
        coordinator.onNetworkChanged(availableNetwork(generation = 2L))
        host.advance()

        assertEquals(1, host.session.livenessCalls)
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(1, host.session.connectCalls)
    }

    @Test
    fun aConfirmedNetworkSwitchWithoutAHealthyProbeDrivesReconnect() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.session.livenessResult = false
        coordinator.onNetworkChanged(availableNetwork(generation = 2L))
        host.advance()

        assertEquals(1, host.session.livenessCalls)
        assertEquals(
            ConnectionLivenessPolicy.NETWORK_SWITCH_UNVERIFIED_DETAIL,
            host.connectionFailures.last().detail,
        )
        assertTrue(host.session.connectCalls >= 2)
    }

    @Test
    fun aLivenessProbeCannotResurrectASessionAfterTheUserDisconnects() {
        val gate = CompletableDeferred<Unit>()
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.session.livenessResult = false
        host.session.livenessGate = { gate.await() }
        coordinator.onNetworkChanged(availableNetwork(generation = 2L))
        host.advance()
        assertEquals(1, host.session.livenessCalls)

        coordinator.requestDisconnect()
        host.advance()
        val connectsBeforeRelease = host.session.connectCalls

        gate.complete(Unit)
        host.advance()

        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertTrue(host.sessionGeneration.isDisconnectRequested)
        assertEquals(connectsBeforeRelease, host.session.connectCalls)
        assertTrue(
            host.connectionFailures.none {
                it.detail == ConnectionLivenessPolicy.NETWORK_SWITCH_UNVERIFIED_DETAIL
            },
        )
    }

    @Test
    fun aStaleProbeFromAnEarlierSwitchCannotEndAHealthyNewerSwitch() {
        val gate = CompletableDeferred<Unit>()
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()

        // Switch to B: its probe hangs and is set to fail once released.
        host.session.livenessResult = false
        host.session.livenessGate = { gate.await() }
        coordinator.onNetworkChanged(availableNetwork(generation = 2L))
        host.advance()
        assertEquals(1, host.session.livenessCalls)

        // Switch again to C: its probe answers immediately and must survive.
        host.session.livenessGate = null
        host.session.livenessResult = true
        coordinator.onNetworkChanged(availableNetwork(generation = 3L))
        host.advance()

        gate.complete(Unit)
        host.advance()

        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(1, host.session.connectCalls)
        assertTrue(host.connectionFailures.isEmpty())
    }

    @Test
    fun staleConnectedCallbacksDoNotFireTheSuccessHook() {
        host.session.emittedStatusOnConnect = null
        coordinator.beginConnection(CONFIG)
        host.advance()
        val firstListener = host.session.listener ?: error("listener was not attached")

        coordinator.beginConnection(CONFIG)
        host.advance()

        firstListener.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))

        assertTrue(host.connectionSucceeded.isEmpty())
    }

    @Test
    fun aBlockedPendingNetworkWaitsWithoutKeepingAnEstablishedSession() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.network.value = false
        coordinator.onNetworkChanged(DefaultNetworkState(isPending = true, isBlocked = true, networkKey = "blocked", generation = 2L))
        host.advance()
        assertEquals(ConnectionPhase.RECONNECTING, host.state.value.status.phase)
        assertEquals(UserMessage.WaitingForNetwork, host.state.value.statusMessage)
    }

    @Test
    fun aNonRespondingProbeExpiresWithinItsBudget() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.network.value = true
        coordinator.onNetworkChanged(availableNetwork(generation = 1L))
        coordinator.beginConnection(CONFIG)
        host.advance()
        val neverAnswered = CompletableDeferred<Unit>()
        host.session.livenessGate = { neverAnswered.await() }
        coordinator.onNetworkChanged(availableNetwork(generation = 2L))
        host.advance()
        host.testScope.advanceTimeBy(ConnectionLivenessPolicy.PROBE_TIMEOUT_MS + 100L)
        assertTrue(host.connectionFailures.any { it.detail == ConnectionLivenessPolicy.NETWORK_SWITCH_UNVERIFIED_DETAIL })
        assertTrue(host.session.connectCalls >= 2)
    }

    @Test
    fun connectionSucceededHookReceivesTheConnectedConfig() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()

        assertEquals(listOf(CONFIG), host.connectionSucceeded)
    }

    @Test
    fun connectionFailureHookReportsTheActingFailure() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.network.value = false
        coordinator.onNetworkChanged(false)

        val failure = host.connectionFailures.last()
        assertEquals(ConnectionPhase.DISCONNECTED, failure.phase)
        assertEquals(ConnectionLivenessPolicy.NETWORK_LOST_DETAIL, failure.detail)
        assertTrue(failure.retryable)
    }

    @Test
    fun connectionAttemptHookReportsEveryAttempt() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()

        assertEquals(listOf(false), host.connectionAttempts)
    }

    private fun availableNetwork(generation: Long) =
        DefaultNetworkState(
            isAvailable = true,
            networkKey = "network-$generation",
            generation = generation,
        )

    @Test
    fun silentTransportCloseStartsRecoveryWithoutNetworkChange() {
        host.network.value = false // Keep recovery waiting so it cannot obscure the failure.
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)

        host.session.transportConnected = false
        host.testScope.advanceTimeBy(1_001L)

        assertEquals(ConnectionPhase.RECONNECTING, host.state.value.status.phase)
        assertTrue(host.state.value.status.retryable)
        assertEquals(1, host.transportDisconnects)
        assertEquals(0, host.session.livenessCalls)
        assertEquals(1, host.session.connectCalls)
        assertEquals(1, host.microphoneStops)
        assertTrue(host.playbackStops >= 1)
        host.testScope.advanceTimeBy(31_000L)
        assertEquals(1, host.transportDisconnects)
    }

    @Test
    fun healthyOrUnknownTransportDoesNotProbeOrReconnectAnIdleChannel() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.testScope.advanceTimeBy(31_000L)
        host.session.transportConnected = true
        host.testScope.advanceTimeBy(31_000L)

        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(0, host.transportDisconnects)
        assertEquals(0, host.session.livenessCalls)
        assertEquals(1, host.session.connectCalls)
    }

    @Test
    fun disconnectBeforeTransportPollDoesNotRestartOrRecordAStaleFailure() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.session.transportConnected = false
        coordinator.requestDisconnect()
        host.testScope.advanceTimeBy(1_001L)

        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertEquals(0, host.transportDisconnects)
        assertEquals(1, host.session.connectCalls)
    }

    @Test
    fun disconnectDuringTransportReadDiscardsTheObservedFailure() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.session.transportReadAction = {
            host.session.transportReadAction = {}
            coordinator.requestDisconnect()
        }
        host.session.transportConnected = false
        host.testScope.advanceTimeBy(1_001L)

        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertEquals(0, host.transportDisconnects)
        assertTrue(host.connectionFailures.isEmpty())
    }

    @Test
    fun pendingServerDisconnectRetainsTerminalCallbackSemantics() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        // The protocol masks its closed socket until the kick/ban callback arrives.
        host.session.transportConnected = null
        host.testScope.advanceTimeBy(1_001L)
        host.session.listener!!.onStatusChanged(ConnectionStatus(ConnectionPhase.DISCONNECTED, retryable = false))
        host.advance()
        host.runPendingTeardowns()

        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertEquals(0, host.transportDisconnects)
        assertEquals(1, host.session.connectCalls)
        assertTrue(host.stopSelfRequested)
    }

    @Test
    fun silentLossIsReportedOnceAndAHealthyReplacementSurvives() {
        val lost =
            FakeSession().apply {
                transportConnected = true
                emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
            }
        val healthy =
            FakeSession().apply {
                transportConnected = true
                emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
            }
        var factories = 0
        coordinator = ConnectionCoordinator(host) { if (factories++ == 0) lost else healthy }
        host.network.value = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        lost.transportConnected = false
        host.testScope.advanceTimeBy(32_000L)

        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(1, host.transportDisconnects)
        assertEquals(1, lost.connectCalls)
        assertEquals(1, healthy.connectCalls)
        assertEquals(1, host.manualSessionStarts)
        assertEquals(ConnectionLivenessPolicy.TRANSPORT_LOST_DETAIL, host.connectionFailures.single().detail)
    }

    @Test
    fun watchExecutionGapIsReportedOnceWithIntegerSeconds() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)

        host.nowMs = 5_000L
        host.testScope.advanceTimeBy(1_001L)
        assertTrue(host.watchGaps.isEmpty())

        host.nowMs = 16_000L
        host.testScope.advanceTimeBy(1_001L)
        assertEquals(listOf(11L), host.watchGaps)

        repeat(5) {
            host.nowMs += 1_000L
            host.testScope.advanceTimeBy(1_001L)
        }

        assertEquals(listOf(11L), host.watchGaps)
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(0, host.transportDisconnects)
    }

    @Test
    fun aBackwardsClockReadingIsNotReportedAsAGap() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.nowMs = 1_000L
        host.testScope.advanceTimeBy(1_001L)
        host.nowMs = 400L
        host.testScope.advanceTimeBy(1_001L)
        host.nowMs = 1_400L
        host.testScope.advanceTimeBy(1_001L)

        assertTrue(host.watchGaps.isEmpty())
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
    }

    @Test
    fun aGapFromAReplacedListenerIsNotRecorded() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()

        host.clockAction = {
            host.clockAction = {}
            coordinator.requestDisconnect()
        }
        host.nowMs = 30_000L
        host.testScope.advanceTimeBy(1_001L)

        assertTrue(host.watchGaps.isEmpty())
        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
    }

    @Test
    fun aNewSessionWatchStartsWithAFreshGapBaseline() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        host.session.transportConnected = true
        coordinator.beginConnection(CONFIG)
        host.advance()
        coordinator.requestDisconnect()
        host.advance()

        host.nowMs = 600_000L
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)

        host.nowMs += 1_000L
        host.testScope.advanceTimeBy(1_001L)
        assertTrue(host.watchGaps.isEmpty())
    }

    @Test
    fun aTaskRemovalExitNotifiesAndReleasesTheSessionBeforeAudioCleanup() {
        connectEstablished()

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        // Voice was detached synchronously; server notification must not wait
        // for potentially slow native audio cleanup.
        assertEquals(listOf("disconnectBounded", "close", "stopMicrophone"), host.timeline)
        assertEquals(1, host.session.boundedDisconnectCalls)
        assertEquals("Task removed", host.session.boundedDisconnectReason)
        assertTrue(host.session.voiceSourceDetached)
        host.runPendingTeardowns()
        assertTrue(host.stopSelfRequested)
        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
    }

    @Test
    fun captureCleanupDoesNotReduceTheProtocolExitBudget() {
        connectEstablished()
        host.microphoneStopAction = { host.nowMs += 500L }

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        assertEquals(EXIT_BUDGET_MS, host.session.boundedDisconnectBudgetMs)
    }

    @Test
    fun audioCleanupThatConsumesTheBudgetDoesNotSkipTheProtocolExit() {
        connectEstablished()
        host.microphoneStopAction = { host.nowMs += EXIT_BUDGET_MS }

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        assertEquals(1, host.session.boundedDisconnectCalls)
        assertTrue(host.diagnostics.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
        assertEquals(1, host.session.closeCalls)
        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertTrue("timeout left the playback resources running", host.playbackStops > 0)
        host.runPendingTeardowns()
        assertTrue(host.stopSelfRequested)
    }

    @Test
    fun aConfirmedExitReportsSendingAndConfirmationSeparately() {
        connectEstablished()
        host.session.boundedDisconnectOutcome = DisconnectOutcome.SERVER_CONFIRMED

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        assertEquals(
            listOf(
                DiagnosticEventKind.EXIT_STARTED,
                DiagnosticEventKind.EXIT_REQUEST_SENT,
                DiagnosticEventKind.EXIT_SERVER_CONFIRMED,
            ),
            host.diagnostics,
        )
    }

    @Test
    fun aLostConfirmationIsReportedAsTimedOutAndNeverAsConfirmed() {
        connectEstablished()
        host.session.boundedDisconnectOutcome = DisconnectOutcome.TIMED_OUT

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        assertTrue(host.diagnostics.contains(DiagnosticEventKind.EXIT_TIMED_OUT))
        assertFalse(host.diagnostics.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
        assertEquals(1, host.session.closeCalls)
    }

    @Test
    fun repeatedExitRequestsSendOneLeaveCommandAndStayIdempotent() {
        connectEstablished()

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        coordinator.requestExit(ExitTrigger.USER_DISCONNECT)
        host.advance()

        assertEquals(1, host.session.boundedDisconnectCalls)
        assertEquals(1, host.session.closeCalls)
        assertEquals(1, host.pendingTeardowns.size)
        host.runPendingTeardowns()
        assertTrue(host.stopSelfRequested)
        assertEquals(1, host.removeForegroundNotificationCalls)
    }

    @Test
    fun anExitedSessionCannotBeRevivedByItsOldListener() {
        connectEstablished()
        val stale = host.session.listener ?: error("listener was not attached")
        val attemptsBefore = host.connectionAttempts.size

        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        stale.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
        stale.onSnapshotChanged(SessionSnapshot.Empty)
        stale.onVoiceFrame(VOICE_FRAME)

        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertEquals(attemptsBefore, host.connectionAttempts.size)
        assertEquals(0, host.submittedVoiceFrames)
    }

    @Test
    fun networkRecoveryAfterAnExitDoesNotRestartTheSession() {
        connectEstablished()
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()
        val attemptsBefore = host.connectionAttempts.size

        host.network.value = false
        coordinator.onNetworkChanged(false)
        host.network.value = true
        coordinator.onNetworkChanged(true)
        host.advance()

        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
        assertEquals(attemptsBefore, host.connectionAttempts.size)
    }

    @Test
    fun aQueuedExitTeardownCannotStopASessionThatANewerRequestOwns() {
        connectEstablished()
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()
        assertEquals(1, host.pendingTeardowns.size)

        // The user reopens and connects before the queued main-thread teardown runs.
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.runPendingTeardowns()

        assertFalse(host.stopSelfRequested)
        assertEquals(0, host.removeForegroundNotificationCalls)
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
    }

    @Test
    fun anOldExitTeardownCannotStopANewerExitThatIsStillRunning() {
        connectEstablished()
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()
        val oldTeardown = host.pendingTeardowns.single()

        // Reconnect, then disconnect again while the second exit is still waiting
        // for its confirmation.
        coordinator.beginConnection(CONFIG)
        host.advance()
        val gate = CompletableDeferred<Unit>()
        host.session.boundedDisconnectGate = { gate.await() }
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        oldTeardown.invoke()

        assertFalse("an old exit stopped the service under a newer exit", host.stopSelfRequested)

        gate.complete(Unit)
        host.advance()
        host.runPendingTeardowns()
        assertTrue(host.stopSelfRequested)
    }

    @Test
    fun destructionReleasesLocallyWithoutSendingAnExitCommand() {
        connectEstablished()

        coordinator.close()

        assertEquals(0, host.session.boundedDisconnectCalls)
        assertEquals(1, host.session.closeCalls)
        assertFalse(host.sessionGeneration.isActive(1L))
    }

    @Test
    fun cancelingANewRequestWhileThePreviousExitRunsStillFinishesTheLatestExit() {
        connectEstablished()
        val gate = CompletableDeferred<Unit>()
        host.session.boundedDisconnectGate = { gate.await() }
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()

        coordinator.beginConnection(CONFIG)
        host.advance()
        coordinator.requestDisconnect()
        gate.complete(Unit)
        host.advance()
        host.runPendingTeardowns()

        assertEquals(1, host.session.boundedDisconnectCalls)
        assertTrue("the latest exit lost its teardown owner", host.stopSelfRequested)
        assertEquals(ConnectionPhase.DISCONNECTED, host.state.value.status.phase)
    }

    @Test
    fun aQueuedTeardownIsInvalidAfterCoordinatorDestruction() {
        connectEstablished()
        coordinator.requestExit(ExitTrigger.TASK_REMOVED)
        host.advance()
        coordinator.close()
        host.runPendingTeardowns()

        assertFalse(host.stopSelfRequested)
        assertEquals(0, host.removeForegroundNotificationCalls)
    }

    private fun connectEstablished() {
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        host.timeline.clear()
    }

    @Test
    fun aRefusedConnectionNeverRetriesAutomaticallyEvenAfterFiveMinutes() {
        refuseFirstConnectWithClones()
        coordinator.beginConnection(CONFIG)
        host.advance()
        advance(300_000L)
        assertEquals(ConnectionPhase.ERROR, host.state.value.status.phase)
        assertEquals(UserMessage.ConnectionLimitWait, host.state.value.statusMessage)
        assertEquals(1, host.session.connectCalls)
        assertFalse(host.state.value.status.retryable)
        assertFalse(host.diagnostics.contains(DiagnosticEventKind.CLONES_RECOVERY_STARTED))
    }

    @Test
    fun aUserCanStartOneNewAttemptAfterARefusal() {
        refuseFirstConnectWithClones()
        host.session.emittedStatusOnConnect = ConnectionStatus(ConnectionPhase.CONNECTED)
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(ConnectionPhase.ERROR, host.state.value.status.phase)
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.runPendingTeardowns()
        assertEquals(ConnectionPhase.CONNECTED, host.state.value.status.phase)
        assertEquals(2, host.session.connectCalls)
        assertFalse(host.stopSelfRequested)
    }

    @Test
    fun aRefusedCallbackCannotWriteIntoAReplacementRequest() {
        var observedClient: Int? = null
        host.session.connectAction = {
            host.session.connectAction = {}
            val old = host.session.listener!!
            old.onStatusChanged(ConnectionStatus(ConnectionPhase.ERROR, category = ConnectionFailureCategory.CLONES_LIMIT))
            coordinator.beginConnection(CONFIG.copy(port = 9988))
            old.onSnapshotChanged(SessionSnapshot.Empty.copy(ownClientId = 777))
            observedClient = host.state.value.snapshot.ownClientId
        }
        coordinator.beginConnection(CONFIG)
        host.advance()
        assertEquals(SessionSnapshot.Empty.ownClientId, observedClient)
        assertEquals("example.invalid:9988", host.state.value.serverLabel)
    }

    @Test
    fun aLimitRefusalAlsoStopsEstablishedSessionRecovery() {
        connectEstablished()
        host.session.listener!!.onStatusChanged(
            ConnectionStatus(ConnectionPhase.ERROR, retryable = true, category = ConnectionFailureCategory.CLONES_LIMIT),
        )
        host.advance()
        advance(300_000L)
        assertEquals(1, host.session.connectCalls)
        assertEquals(ConnectionPhase.ERROR, host.state.value.status.phase)
        assertEquals(UserMessage.ConnectionLimitWait, host.state.value.statusMessage)
    }

    @Test
    fun aFirstConnectThatFailsWithANetworkErrorStaysTerminal() {
        host.session.connectAction = { throw IOException("offline") }
        coordinator.beginConnection(CONFIG)
        host.advance()
        host.runPendingTeardowns()
        assertEquals(ConnectionPhase.ERROR, host.state.value.status.phase)
        assertTrue(host.stopSelfRequested)
    }

    /**
     * The refusal is observed through the protocol listener while the blocking
     * connect is still running, which is the order a real handshake produces, and
     * the call that follows it fails with an unrelated transport error.
     */
    private fun refuseFirstConnectWithClones() {
        host.session.connectAction = {
            // Only the first attempt is refused; a later request must be able to
            // succeed on the same scripted session.
            host.session.connectAction = {}
            host.session.listener?.onStatusChanged(
                ConnectionStatus(
                    phase = ConnectionPhase.ERROR,
                    detail = "too many clones already connected",
                    retryable = true,
                    category = ConnectionFailureCategory.CLONES_LIMIT,
                ),
            )
            throw IOException("handshake cut")
        }
        host.network.value = true
    }

    /** Advances the virtual dispatcher and the injected monotonic clock together. */
    private fun advance(millis: Long) {
        host.nowMs += millis
        host.testScope.advanceTimeBy(millis)
    }

    private class FakeSession(
        private val timeline: MutableList<String> = mutableListOf(),
    ) : Ts3SessionClient {
        private var transportSignal: Boolean? = null
        var transportReadAction: () -> Unit = {}
        override var transportConnected: Boolean?
            get() {
                transportReadAction()
                return transportSignal
            }
            set(value) {
                transportSignal = value
            }
        var connectAction: () -> Unit = {}
        var emittedStatusOnConnect: ConnectionStatus? =
            ConnectionStatus(ConnectionPhase.CONNECTING)
        var connectCalls = 0
        var listener: Ts3SessionListener? = null
        var connectedConfig: ServerConfig? = null
        var closeCalls = 0
        var boundedDisconnectCalls = 0
        var boundedDisconnectBudgetMs = 0L
        var boundedDisconnectReason: String? = null
        var boundedDisconnectOutcome = DisconnectOutcome.SERVER_CONFIRMED
        var boundedDisconnectGate: (suspend () -> Unit)? = null
        var voiceSourceDetached = false

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) {
            connectCalls++
            connectedConfig = config
            this.listener = listener
            connectAction()
            emittedStatusOnConnect?.let { listener.onStatusChanged(it) }
        }

        override fun setVoiceSource(source: EncodedVoiceSource?) {
            if (source == null) voiceSourceDetached = true
        }

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
            boundedDisconnectCalls++
            boundedDisconnectBudgetMs = timeoutMs
            boundedDisconnectReason = reason
            timeline += "disconnectBounded"
            onRequestSent()
            boundedDisconnectGate?.invoke()
            return boundedDisconnectOutcome
        }

        var livenessResult = true
        var livenessCalls = 0
        var livenessGate: (suspend () -> Unit)? = null

        override suspend fun verifyLiveness(timeoutMs: Long): Boolean {
            livenessCalls++
            val result = livenessResult
            livenessGate?.invoke()
            return result
        }

        override fun close() {
            closeCalls++
            timeline += "close"
        }
    }

    private class FakeHost(
        val timeline: MutableList<String> = mutableListOf(),
    ) : ConnectionCoordinatorHost {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        override val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
        override val sessionMutex = kotlinx.coroutines.sync.Mutex()
        override val sessionGeneration = SessionGeneration()
        override val networkAvailable = MutableStateFlow(false)
        val network: MutableStateFlow<Boolean> get() = networkAvailable
        override val mutableState = MutableStateFlow(TeamSpeakServiceState())
        val state: kotlinx.coroutines.flow.StateFlow<TeamSpeakServiceState> = mutableState
        override val reconnectPolicy = ReconnectPolicy(delaysMs = listOf(10L))
        override val diagnosticsRecorder = DiagnosticsRecorder()
        val session = FakeSession(timeline)
        val scope = serviceScope
        var stopSelfRequested = false
        var removeForegroundNotificationCalls = 0
        var playbackStops = 0
        var microphoneStops = 0
        var manualSessionStarts = 0
        var nowMs = 0L
        var clockAction: () -> Unit = {}
        val watchGaps = mutableListOf<Long>()

        fun readClockMs(): Long {
            clockAction()
            return nowMs
        }

        override fun onManualConnectionStarted() {
            manualSessionStarts++
        }

        override fun conciseMessage(error: Throwable): String = error::class.java.simpleName

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        var microphoneStopAction: () -> Unit = {}

        override suspend fun stopMicrophoneCapture() {
            timeline += "stopMicrophone"
            microphoneStopAction()
        }

        var identityCalls = 0

        override suspend fun createIdentity(): String {
            identityCalls++
            return "identity"
        }

        override fun startAudioRouting() = Unit

        override fun stopAudioRouting() = Unit

        override fun applySelectedPlaybackMuted() = Unit

        override fun startPlayback() = Unit

        override fun stopPlayback() {
            playbackStops++
            playbackStopAction()
        }

        var playbackStopAction: () -> Unit = {}

        override fun clearPlaybackMuted() = Unit

        override fun attachMicrophoneTo(session: Ts3SessionClient) = Unit

        override fun stopMicrophoneImmediately() {
            microphoneStops++
        }

        override fun reconcileMicrophone() = Unit

        override fun applyParticipantAudioSettings(snapshot: SessionSnapshot) = Unit

        var submittedVoiceFrames = 0

        override fun submitVoiceFrame(frame: VoiceFrame) {
            submittedVoiceFrames++
        }

        override fun removeForegroundNotification() {
            removeForegroundNotificationCalls++
        }

        override fun requestStopSelf() {
            stopSelfRequested = true
        }

        val diagnostics = mutableListOf<DiagnosticEventKind>()
        val diagnosticCodes = mutableListOf<Pair<DiagnosticEventKind, Long>>()

        override fun recordDiagnostic(
            kind: DiagnosticEventKind,
            code: Long,
        ) {
            diagnostics += kind
            diagnosticCodes += kind to code
        }

        /** Deferred exactly like the service's main-thread handler hop. */
        val pendingTeardowns = mutableListOf<() -> Unit>()
        var latestExitCheck: (Long) -> Boolean = { true }

        override fun finishExitOnMainThread(exitToken: Long) {
            pendingTeardowns += {
                if (latestExitCheck(exitToken)) {
                    removeForegroundNotificationCalls++
                    stopSelfRequested = true
                }
            }
        }

        override fun finishTerminalOnMainThread(epoch: Long) {
            pendingTeardowns += {
                if (sessionGeneration.isActive(epoch)) {
                    removeForegroundNotificationCalls++
                    stopSelfRequested = true
                }
            }
        }

        fun runPendingTeardowns() {
            val pending = pendingTeardowns.toList()
            pendingTeardowns.clear()
            pending.forEach { it() }
        }

        val connectionAttempts = mutableListOf<Boolean>()
        val connectionSucceeded = mutableListOf<ServerConfig>()
        val connectionFailures = mutableListOf<ConnectionStatus>()
        var transportDisconnects = 0

        override fun onTransportDisconnected() {
            transportDisconnects++
        }

        override fun onWatchExecutionGap(gapSeconds: Long) {
            watchGaps += gapSeconds
        }

        override fun onConnectionAttempt(reconnecting: Boolean) {
            connectionAttempts += reconnecting
        }

        override fun onConnectionSucceeded(config: ServerConfig) {
            connectionSucceeded += config
        }

        override fun onConnectionFailure(status: ConnectionStatus) {
            connectionFailures += status
        }

        fun advance() {
            // Bounded virtual-time advance: the diagnostics refresh loop runs
            // forever while connected, so advanceUntilIdle would never settle.
            testScope.advanceTimeBy(100L)
        }
    }

    private companion object {
        val CONFIG =
            ServerConfig(
                host = "example.invalid",
                port = 9987,
                nickname = "tester",
            )

        val VOICE_FRAME =
            VoiceFrame(
                clientId = 7,
                packetId = 1,
                codec = io.github.ts3mobile.protocol.VoiceCodec.OPUS_VOICE,
                encodedData = ByteArray(2),
                isWhisper = false,
            )

        /** Product budget for one exit; asserted against the coordinator's own value. */
        const val EXIT_BUDGET_MS = 2_000L
    }
}
