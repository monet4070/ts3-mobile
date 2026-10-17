package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
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
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real blocking work that ignores interrupts: only this attempt's close releases it. */
class ClonesRecoveryConcurrencyTest {
    @Test
    fun aRefusedBlockedHandshakeClosesWithoutAnyRetryAndRejectsLateSuccess() {
        val host = Host()
        val blocked =
            Session { listener, closed ->
                listener.onStatusChanged(CLONES)
                awaitCloseIgnoringInterrupts(closed)
                listener.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
            }
        host.sessions += blocked
        withCoordinator(host) { coordinator ->
            coordinator.beginConnection(CONFIG)
            assertTrue(blocked.closed.await(1, TimeUnit.SECONDS))
            assertTrue(blocked.returned.await(1, TimeUnit.SECONDS))
            assertTrue(awaitUntil { host.mutableState.value.statusMessage == UserMessage.ConnectionLimitWait })
            assertEquals(0, host.successes.get())
            assertFalse(host.mutableState.value.status.retryable)
        }
    }

    @Test
    fun aNewRequestDuringOldSocketCloseCannotBeCancelledByOldCleanup() {
        val host = Host()
        val old =
            Session { listener, closed ->
                listener.onStatusChanged(CLONES)
                awaitCloseIgnoringInterrupts(closed)
            }
        val replacement = Session { listener, _ -> listener.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED)) }
        host.sessions += old
        host.sessions += replacement
        withCoordinator(host) { coordinator ->
            old.onClose = {
                old.onClose = null
                coordinator.beginConnection(CONFIG.copy(port = 9988))
            }
            coordinator.beginConnection(CONFIG)
            assertTrue(replacement.returned.await(1, TimeUnit.SECONDS))
            assertTrue(awaitUntil { host.mutableState.value.status.phase == ConnectionPhase.CONNECTED })
            assertEquals("example.invalid:9988", host.mutableState.value.serverLabel)
            assertEquals(1L, replacement.closed.count)
            assertEquals(1, host.successes.get())
        }
    }

    @Test
    fun successDeliveredImmediatelyAfterRefusalCannotBecomeActive() {
        val host = Host()
        val late =
            Session { listener, _ ->
                listener.onStatusChanged(CLONES)
                listener.onStatusChanged(ConnectionStatus(ConnectionPhase.CONNECTED))
            }
        host.sessions += late
        withCoordinator(host) { coordinator ->
            coordinator.beginConnection(CONFIG)
            assertTrue(late.returned.await(1, TimeUnit.SECONDS))
            assertTrue(awaitUntil { host.mutableState.value.statusMessage == UserMessage.ConnectionLimitWait })
            assertEquals(0, host.successes.get())
        }
    }

    private fun withCoordinator(
        host: Host,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        action: (ConnectionCoordinator) -> Unit,
    ) {
        val coordinator =
            ConnectionCoordinator(
                host,
                clockMs = clock,
                sessionFactory = { host.sessions.remove() },
            )
        host.coordinator = coordinator
        try {
            action(coordinator)
        } finally {
            coordinator.close()
            host.sessions.forEach { it.close() }
            host.serviceScope.cancel()
            host.executor.shutdownNow()
            assertTrue(host.executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class Session(val action: (Ts3SessionListener, CountDownLatch) -> Unit) : Ts3SessionClient {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val returned = CountDownLatch(1)
        var onClose: (() -> Unit)? = null

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) {
            entered.countDown()
            try {
                action(listener, closed)
            } finally {
                returned.countDown()
            }
        }

        override fun close() {
            closed.countDown()
            onClose?.invoke()
        }

        override fun disconnect(reason: String) = Unit

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit
    }

    private class Host : ConnectionCoordinatorHost {
        val executor = Executors.newFixedThreadPool(4)
        override val serviceScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        override val sessionMutex = Mutex()
        override val sessionGeneration = SessionGeneration()
        override val networkAvailable = MutableStateFlow(true)
        override val mutableState = MutableStateFlow(TeamSpeakServiceState())
        override val reconnectPolicy = ReconnectPolicy()
        override val diagnosticsRecorder = DiagnosticsRecorder()
        val sessions = ConcurrentLinkedQueue<Session>()
        val events = CopyOnWriteArrayList<Pair<DiagnosticEventKind, Long>>()
        val successes = AtomicInteger()
        var coordinator: ConnectionCoordinator? = null

        override fun conciseMessage(error: Throwable) = "fixture failure"

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() = Unit

        override suspend fun createIdentity() = "fixture identity"

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

        override fun onConnectionSucceeded(config: ServerConfig) {
            successes.incrementAndGet()
        }
    }

    private companion object {
        const val BUDGET_MS = 200L
        val CONFIG = ServerConfig("example.invalid", 9987, "fixture")
        val CLONES = ConnectionStatus(ConnectionPhase.ERROR, retryable = true, category = ConnectionFailureCategory.CLONES_LIMIT)

        fun awaitCloseIgnoringInterrupts(closed: CountDownLatch) {
            while (closed.count != 0L) {
                try {
                    closed.await()
                } catch (_: InterruptedException) {
                    // Close is the cancellation seam.
                }
            }
        }

        fun awaitUntil(condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (System.nanoTime() < deadline) {
                if (condition()) return true
                Thread.sleep(5)
            }
            return condition()
        }
    }
}
