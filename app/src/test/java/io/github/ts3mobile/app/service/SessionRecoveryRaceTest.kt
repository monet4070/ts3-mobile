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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecoveryRaceTest {
    @Test fun lossDuringSuccessfulHandoffStartsExactlyOneMoreAttempt() {
        checkHandoff(exitDuringHandoff = false)
    }

    @Test fun explicitExitDiscardsLossQueuedDuringSuccessfulHandoff() {
        checkHandoff(exitDuringHandoff = true)
    }

    private fun checkHandoff(exitDuringHandoff: Boolean) {
        val host = Host()
        val epoch = host.sessionGeneration.beginSession()
        var attempts = 0
        lateinit var engine: ReconnectEngine
        val delegate =
            object : ReconnectEngine.Delegate {
                override suspend fun performAttempt(epoch: Long): AttemptResult {
                    attempts++
                    host.mutableState.value = TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTED))
                    if (attempts == 1) {
                        engine.launch(LOST, epoch)
                        engine.launch(LOST, epoch)
                        if (exitDuringHandoff) {
                            host.sessionGeneration.invalidate()
                            engine.cancel()
                            host.mutableState.value = TeamSpeakServiceState()
                        }
                    }
                    return AttemptResult.Success
                }

                override suspend fun finishTerminalFailure(
                    status: ConnectionStatus,
                    epoch: Long,
                ) = error("Unexpected terminal result")

                override suspend fun suspendAudioForReconnect(epoch: Long): Boolean = true
            }
        try {
            engine = ReconnectEngine(host, delegate)
            engine.launch(LOST, epoch)
            host.scheduler.advanceTimeBy(1_000)
            host.scheduler.runCurrent()

            assertFalse(engine.isActive)
            assertEquals(if (exitDuringHandoff) 1 else 2, attempts)
            val expectedPhase = if (exitDuringHandoff) ConnectionPhase.DISCONNECTED else ConnectionPhase.CONNECTED
            assertEquals(expectedPhase, host.mutableState.value.status.phase)
        } finally {
            host.serviceScope.cancel()
        }
    }

    @Test fun obsoleteFailureCannotStopReplacementPlaybackOrTransmitGate() {
        val host = Host()
        val prior = FakeSession()
        val next = FakeSession()
        val sessions = ArrayDeque(listOf(prior, next))
        val coordinator = ConnectionCoordinator(host, clockMs = { host.scheduler.currentTime }, sessionFactory = { sessions.removeFirst() })
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val callbackError = AtomicReference<Throwable?>()
        host.failureHook = {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
        }
        val callback = Thread { runCatching { prior.listener.onStatusChanged(LOST) }.onFailure(callbackError::set) }
        try {
            assertTrue(coordinator.beginConnection(CONFIG))
            host.scheduler.runCurrent()
            callback.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            coordinator.requestDisconnect()
            host.scheduler.runCurrent()
            assertEquals(ConnectionPhase.DISCONNECTED, host.mutableState.value.status.phase)
            assertTrue(coordinator.beginConnection(CONFIG.copy(nickname = "replacement")))
            host.scheduler.runCurrent()
            assertTrue(host.playback)
            val microphoneStopsBeforeCallback = host.microphoneStops.get()

            resume.countDown()
            callback.join(5_000)
            assertFalse(callback.isAlive)
            assertNull(callbackError.get())
            host.scheduler.runCurrent()
            assertTrue(host.playback)
            assertEquals(microphoneStopsBeforeCallback, host.microphoneStops.get())
            assertEquals(ConnectionPhase.CONNECTED, host.mutableState.value.status.phase)
        } finally {
            resume.countDown()
            callback.join(5_000)
            coordinator.close()
            host.serviceScope.cancel()
        }
    }

    @Test fun offlineObservationCannotOverwriteACompletedExit() {
        val state = GatedState(MutableStateFlow(TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.RECONNECTING))))
        val host = Host(state)
        val coordinator = ConnectionCoordinator(host, clockMs = { host.scheduler.currentTime })
        val observerError = AtomicReference<Throwable?>()
        val observer = Thread({ runCatching { coordinator.onNetworkChanged(false) }.onFailure(observerError::set) }, OBSERVER_THREAD)
        host.sessionGeneration.beginSession()
        observer.start()
        try {
            assertTrue(state.entered.await(5, TimeUnit.SECONDS))
            coordinator.requestDisconnect()
            host.scheduler.runCurrent()
            assertEquals(ConnectionPhase.DISCONNECTED, state.value.status.phase)
            assertTrue(host.sessionGeneration.isDisconnectRequested)

            state.resume.countDown()
            observer.join(5_000)
            assertFalse(observer.isAlive)
            assertNull(observerError.get())
            assertEquals(ConnectionPhase.DISCONNECTED, state.value.status.phase)
        } finally {
            state.resume.countDown()
            observer.join(5_000)
            coordinator.close()
            host.serviceScope.cancel()
        }
    }

    private class GatedState(private val delegate: MutableStateFlow<TeamSpeakServiceState>) :
        MutableStateFlow<TeamSpeakServiceState> by delegate {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        private var observerReads = 0

        override var value: TeamSpeakServiceState
            get() {
                val snapshot = delegate.value
                if (Thread.currentThread().name == OBSERVER_THREAD && ++observerReads == 2) {
                    entered.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return snapshot
            }
            set(value) {
                delegate.value = value
            }
    }

    private class FakeSession : Ts3SessionClient {
        lateinit var listener: Ts3SessionListener

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) {
            this.listener = listener
            listener.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
        }

        override suspend fun disconnectBounded(
            timeoutMs: Long,
            reason: String,
            onRequestSent: () -> Unit,
        ): DisconnectOutcome = DisconnectOutcome.SERVER_CONFIRMED

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit

        override fun disconnect(reason: String) = Unit

        override fun close() = Unit
    }

    private class Host(override val mutableState: MutableStateFlow<TeamSpeakServiceState> = MutableStateFlow(TeamSpeakServiceState())) :
        ConnectionCoordinatorHost {
        val scheduler = TestCoroutineScheduler()
        override val serviceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        override val sessionMutex = Mutex()
        override val sessionGeneration = SessionGeneration()
        override val networkAvailable = MutableStateFlow(true)
        override val reconnectPolicy = ReconnectPolicy(listOf(10L))
        override val diagnosticsRecorder = DiagnosticsRecorder()

        @Volatile var playback = false
        val microphoneStops = AtomicInteger()
        var failureHook: () -> Unit = {}

        override fun conciseMessage(error: Throwable): String = error.javaClass.simpleName

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() = Unit

        override suspend fun createIdentity() = "fixture"

        override fun startAudioRouting() = Unit

        override fun stopAudioRouting() = Unit

        override fun applySelectedPlaybackMuted() = Unit

        override suspend fun startPlayback() {
            playback = true
        }

        override fun stopPlayback() {
            playback = false
        }

        override fun clearPlaybackMuted() = Unit

        override fun attachMicrophoneTo(session: Ts3SessionClient) = Unit

        override fun stopMicrophoneImmediately() {
            microphoneStops.incrementAndGet()
        }

        override fun reconcileMicrophone() = Unit

        override fun applyParticipantAudioSettings(snapshot: SessionSnapshot) = Unit

        override fun submitVoiceFrame(frame: VoiceFrame) = Unit

        override fun removeForegroundNotification() = Unit

        override fun requestStopSelf() = Unit

        override fun onConnectionFailure(status: ConnectionStatus) = failureHook()
    }

    private companion object {
        val CONFIG = ServerConfig("fixture.example.invalid", 9987, "fixture")
        val LOST = ConnectionStatus(ConnectionPhase.ERROR, retryable = true)
        const val OBSERVER_THREAD = "fixture-network-observer"
    }
}
