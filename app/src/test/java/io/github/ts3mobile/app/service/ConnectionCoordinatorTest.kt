package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.VoiceFrame
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        coordinator = ConnectionCoordinator(host, sessionFactory = { host.session })
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
    fun terminalFailureStopsForegroundAndRequestsStopSelf() {
        host.session.connectAction = { error("boom") }
        coordinator.beginConnection(CONFIG)
        host.advance()

        assertEquals(ConnectionPhase.ERROR, host.state.value.status.phase)
        assertTrue(host.stopSelfRequested)
        assertTrue(host.removeForegroundNotificationCalls >= 1)
    }

    private class FakeSession : Ts3SessionClient {
        var connectAction: () -> Unit = {}
        var emittedStatusOnConnect: ConnectionStatus? =
            ConnectionStatus(ConnectionPhase.CONNECTING)
        var connectCalls = 0
        var listener: Ts3SessionListener? = null

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) {
            connectCalls++
            this.listener = listener
            connectAction()
            emittedStatusOnConnect?.let { listener.onStatusChanged(it) }
        }

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit

        override fun disconnect(reason: String) = Unit

        override fun close() = Unit
    }

    private class FakeHost : ConnectionCoordinatorHost {
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
        val session = FakeSession()
        val scope = serviceScope
        var stopSelfRequested = false
        var removeForegroundNotificationCalls = 0

        override fun conciseMessage(error: Throwable): String = error::class.java.simpleName

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() = Unit

        override suspend fun createIdentity(): String = "identity"

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

        override fun removeForegroundNotification() {
            removeForegroundNotificationCalls++
        }

        override fun requestStopSelf() {
            stopSelfRequested = true
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
    }
}
