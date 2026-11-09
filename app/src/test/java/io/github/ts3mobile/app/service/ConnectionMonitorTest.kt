package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionMonitorTest {
    @Test fun pendingCapabilitiesAndBlockedPendingRetainTheOriginalNetworkClassification() {
        val monitor = ConnectionMonitor { 0 }
        assertEquals(NetworkTransition.UNCHANGED, monitor.networkChanged(available(1)))
        val pending = DefaultNetworkState(isPending = true, generation = 2)
        assertEquals(NetworkTransition.PENDING, monitor.networkChanged(pending))
        assertFalse(monitor.isCurrentNetwork(2))
        assertEquals(NetworkTransition.SWITCHED, monitor.networkChanged(available(2)))
        assertEquals(NetworkTransition.UNAVAILABLE, monitor.networkChanged(pending.copy(isBlocked = true)))
        assertEquals(NetworkTransition.UNCHANGED, monitor.networkChanged(available(2)))
    }

    @Test fun duplicateNetworksNeverProbeAndABCResultsKeepTheirOwnGeneration() {
        val monitor = ConnectionMonitor { 0 }
        assertEquals(NetworkTransition.UNCHANGED, monitor.networkChanged(available(1)))
        assertEquals(NetworkTransition.UNCHANGED, monitor.networkChanged(available(1)))
        assertEquals(NetworkTransition.SWITCHED, monitor.networkChanged(available(2)))
        assertEquals(NetworkTransition.SWITCHED, monitor.networkChanged(available(3)))
        assertFalse(monitor.isCurrentNetwork(2))
        assertTrue(monitor.isCurrentNetwork(3))
    }

    @Test fun probesReturnOnlyBoundFactsAndPassTheUnchangedProtocolBudget() =
        runTest {
            val session = Session()
            val binding = binding(session)
            val monitor = ConnectionMonitor { testScheduler.currentTime }
            val observation = monitor.probe(binding, 12)
            assertSame(binding, observation.binding)
            assertSame(session, observation.binding.session)
            assertEquals(7L, observation.binding.epoch)
            assertEquals(12L, observation.networkGeneration)
            assertTrue(observation.alive)
            assertEquals(listOf(5000L), session.budgets)
            session.probe = { throw IOException("Synthetic probe failure") }
            assertFalse(monitor.probe(binding, 13).alive)
        }

    @Test fun suspendedProbeExpiresAtFiveSecondsWithoutAnyRecoverySideEffect() =
        runTest {
            val session =
                Session().apply {
                    probe = {
                        delay(6000)
                        true
                    }
                }
            val monitor = ConnectionMonitor { testScheduler.currentTime }
            val start = testScheduler.currentTime
            assertFalse(monitor.probe(binding(session), 2).alive)
            assertEquals(5000L, testScheduler.currentTime - start)
        }

    @Test fun callerCancellationRemainsCancellationAndIsNotAFalseNetworkResult() =
        runTest {
            val never = CompletableDeferred<Unit>()
            val session =
                Session().apply {
                    probe = {
                        never.await()
                        true
                    }
                }
            val monitor = ConnectionMonitor { testScheduler.currentTime }
            val result = backgroundScope.async { monitor.probe(binding(session), 2) }
            runCurrent()
            result.cancel()
            runCurrent()
            assertTrue(result.isCancelled)
            assertEquals(1, session.budgets.size)
        }

    @Test fun silentHealthyOrUnknownTransportOnlyEmitsTicksAtTheOriginalOneSecondCadence() =
        runTest {
            val session = Session()
            val observations = mutableListOf<ConnectionWatchObservation>()
            val monitor = ConnectionMonitor { testScheduler.currentTime }
            val job = backgroundScope.launch { monitor.watch(binding(session), { true }, observations::add) }
            runCurrent()
            assertEquals(1, observations.size)
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, observations.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, observations.size)
            session.transportConnected = true
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(3, observations.size)
            assertTrue(observations.all { it is ConnectionWatchObservation.Tick })
            assertTrue(session.budgets.isEmpty())
            job.cancel()
        }

    @Test fun transportClosureProducesOneBoundObservationAndDoesNotDisconnectItself() =
        runTest {
            val session = Session().apply { transportConnected = false }
            val binding = binding(session)
            val observations = mutableListOf<ConnectionWatchObservation>()
            val monitor = ConnectionMonitor { testScheduler.currentTime }
            val job = backgroundScope.launch { monitor.watch(binding, { true }, observations::add) }
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(listOf(ConnectionWatchObservation.TransportClosed(binding)), observations)
            assertTrue(session.budgets.isEmpty())
        }

    @Test fun bindingReplacementDuringTransportReadDiscardsTheLateFact() =
        runTest {
            var current = true
            val session =
                Session().apply {
                    transportConnected = false
                    onRead = { current = false }
                }
            val observations = mutableListOf<ConnectionWatchObservation>()
            val monitor = ConnectionMonitor { testScheduler.currentTime }
            backgroundScope.launch { monitor.watch(binding(session), { current }, observations::add) }
            runCurrent()
            assertTrue(observations.isEmpty())
        }

    @Test fun gapsUseTenSecondThresholdIntegerSecondsAndAFreshBaselineForEachWatch() =
        runTest {
            var now = 0L
            val session = Session()
            val observations = mutableListOf<ConnectionWatchObservation>()
            val monitor = ConnectionMonitor { now }
            val binding = binding(session)
            val first = backgroundScope.launch { monitor.watch(binding, { true }, observations::add) }
            runCurrent()
            now = 9999
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(observations.none { it is ConnectionWatchObservation.ExecutionGap })
            now = 20500
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf(10L), observations.filterIsInstance<ConnectionWatchObservation.ExecutionGap>().map { it.seconds })
            now = 10
            advanceTimeBy(1000)
            runCurrent()
            first.cancel()
            runCurrent()
            now = 600000
            backgroundScope.launch { monitor.watch(binding, { true }, observations::add) }
            runCurrent()
            assertEquals(1, observations.filterIsInstance<ConnectionWatchObservation.ExecutionGap>().size)
        }

    @Test fun replacementDuringClockObservationDoesNotPublishAGapOrReadTransport() =
        runTest {
            var current = true
            var reads = 0
            val session = Session().apply { onRead = { error("Stale observer must not read transport") } }
            val monitor =
                ConnectionMonitor {
                    if (++reads == 1) {
                        0L
                    } else {
                        current = false
                        30000L
                    }
                }
            val observations = mutableListOf<ConnectionWatchObservation>()
            backgroundScope.launch { monitor.watch(binding(session), { current }, observations::add) }
            runCurrent()
            assertTrue(observations.isEmpty())
        }

    private fun available(generation: Long) = DefaultNetworkState(isAvailable = true, generation = generation)

    private fun binding(session: Session) =
        MonitoredSession(
            7,
            session,
            object : Ts3SessionListener {
                override fun onStatusChanged(status: ConnectionStatus) = error("Observer must not publish status")

                override fun onSnapshotChanged(snapshot: SessionSnapshot) = error("Observer must not publish snapshots")

                override fun onVoiceFrame(frame: VoiceFrame) = error("Observer must not deliver media")
            },
        )

    private class Session : Ts3SessionClient {
        var probe: suspend () -> Boolean = { true }
        val budgets = mutableListOf<Long>()
        var onRead: () -> Unit = {}
        private var signal: Boolean? = null
        override var transportConnected: Boolean?
            get() {
                onRead()
                return signal
            }
            set(value) {
                signal = value
            }

        override suspend fun verifyLiveness(timeoutMs: Long): Boolean {
            budgets += timeoutMs
            return probe()
        }

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) = error(
            "Observer must not connect",
        )

        override fun setVoiceSource(source: EncodedVoiceSource?) = error("Observer must not touch audio")

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = error("Observer must not join channels")

        override fun disconnect(reason: String) = error("Observer must not disconnect")

        override fun close() = error("Observer must not close transport")
    }
}
