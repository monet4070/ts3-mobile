package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioReceiveDiagnosticsTest {
    @Test fun periodicPeaksResetWithoutDestroyingLegacyTotalsOrCumulativeCounters() {
        val stats = ReceiveStats()
        val diagnostics = PlaybackDiagnostics(stats)
        diagnostics.begin()
        stats.stalePackets.addAndGet(3)
        stats.maximum(stats.maxInputAgeNanos, 42)
        val first = diagnostics.take(true)
        assertEquals(3L, first.counters[AudioReceiveCounter.STALE])
        assertEquals(42L, first.peaks[AudioReceivePeak.INPUT_AGE_NS])
        stats.maximum(stats.maxInputAgeNanos, 8)
        val second = diagnostics.take(true)
        assertEquals(3L, second.counters[AudioReceiveCounter.STALE])
        assertEquals(8L, second.peaks[AudioReceivePeak.INPUT_AGE_NS])
        assertEquals(42L, stats.maxInputAgeNanos.get())
        assertTrue(stats.summaryLine().contains("stale=3"))
    }

    @Test fun playbackGenerationsHaveSeparateBaselinesAndStopEmitsExactlyOneFinalSnapshot() {
        val stats = ReceiveStats()
        val diagnostics = PlaybackDiagnostics(stats)
        val stopped = mutableListOf<AudioReceiveDiagnostics>()
        stats.observer = observer { stopped.add(it) }
        diagnostics.begin()
        stats.plcPackets.addAndGet(4)
        diagnostics.end()
        diagnostics.end()
        assertEquals(1, stopped.size)
        assertFalse(stopped.single().active)
        assertEquals(4L, stopped.single().counters[AudioReceiveCounter.PLC])
        diagnostics.begin()
        stats.plcPackets.incrementAndGet()
        val next = diagnostics.take(true)
        assertEquals(2L, next.generation)
        assertEquals(1L, next.counters[AudioReceiveCounter.PLC])
    }

    @Test fun finalCallbackDoesNotHoldTheSamplingLock() {
        val stats = ReceiveStats()
        val diagnostics = PlaybackDiagnostics(stats)
        diagnostics.begin()
        val read = CountDownLatch(1)
        stats.observer =
            observer {
                Thread {
                    diagnostics.take(false)
                    read.countDown()
                }.start()
                assertTrue("Final observer must not deadlock a concurrent sampler", read.await(2, TimeUnit.SECONDS))
            }
        diagnostics.end()
    }

    @Test fun arrivalGapIsPerTalkerAndIncludesNaturalSilenceWithoutClaimingPacketLoss() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        queue.offer(packet(1, 0, 0))
        queue.offer(packet(2, 0, 10_000_000))
        queue.offer(packet(1, 1, 80_000_000))
        val peak = queue.budget.stats.takePeaks()
        assertEquals(80_000_000L, peak[AudioReceivePeak.ARRIVAL_GAP_NS])
        assertEquals(1L, queue.budget.stats.counters()[AudioReceiveCounter.ARRIVAL_GAPS_OVER_60_MS])
        assertEquals(3L, queue.budget.stats.counters()[AudioReceiveCounter.ACCEPTED])
        queue.clear()
        queue.offer(packet(1, 2, 5_000_000_000))
        assertEquals(0L, queue.budget.stats.takePeaks()[AudioReceivePeak.ARRIVAL_GAP_NS])
    }

    @Test fun outputWritesAndDrainTimeoutAreVisibleWithoutChangingPartialWriteBehavior() {
        val stats = ReceiveStats()
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(2, 0)) }
        val timeline = PlaybackTimeline(output, receiveStats = stats)
        timeline.start(0)
        timeline.tick(shortArrayOf(1, 2, 3, 4, 5), 0)
        timeline.tick(null, 10_000_000)
        timeline.tick(null, 20_000_000)
        timeline.tick(null, 80_000_000)
        timeline.tick(null, 600_000_000)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), output.accepted)
        assertEquals(5L, stats.counters()[AudioReceiveCounter.OUTPUT_SAMPLES])
        assertEquals(1L, stats.counters()[AudioReceiveCounter.ZERO_WRITES])
        assertEquals(1L, stats.counters()[AudioReceiveCounter.DRAIN_TIMEOUTS])
        assertEquals(5L, stats.takePeaks()[AudioReceivePeak.OUTPUT_BACKLOG_SAMPLES])
    }

    @Test fun actualRouteObservationsContainOnlyCoarseKindsAndDoNotRepeatUnchangedRoutes() {
        val stats = ReceiveStats()
        val observations = mutableListOf<Long>()
        stats.observer =
            object : AudioDiagnosticsObserver {
                override fun onEvent(
                    kind: AudioDiagnosticEventKind,
                    value: Long,
                ) {
                    observations.add(value)
                }

                override fun onStopped(snapshot: AudioReceiveDiagnostics) = Unit
            }
        stats.observeRoute(AudioRouteKind.BLUETOOTH)
        stats.observeRoute(AudioRouteKind.BLUETOOTH)
        stats.observeRoute(null)
        assertEquals(listOf(AudioRouteKind.BLUETOOTH.ordinal.toLong(), -1L), observations)
        assertEquals(2L, stats.counters()[AudioReceiveCounter.ACTUAL_ROUTE_OBSERVATIONS])
    }

    @Test fun failedUnderrunReadIsExplicitAndCannotPreventOutputCleanup() {
        val stats = ReceiveStats()
        assertEquals(7, stats.measureUnderruns(7) { throw IllegalStateException("Fixture measurement unavailable") })
        assertEquals(1L, stats.counters()[AudioReceiveCounter.DIAGNOSTIC_READ_FAILURES])
        assertEquals(9, stats.measureUnderruns(7) { 9 })
        assertEquals(2L, stats.counters()[AudioReceiveCounter.OUTPUT_UNDERRUNS])
    }

    @Test fun pollingBetweenWorkerEndAndItsCallbackCannotDrainAwayTheFinalPeaks() {
        val stats = ReceiveStats()
        val diagnostics = PlaybackDiagnostics(stats)
        diagnostics.begin()
        stats.peak(AudioReceivePeak.WORKER_GAP_NS, 80_000_000)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        stats.observer =
            observer {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        val ending = Thread { diagnostics.end() }
        ending.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val polled = diagnostics.take(true)
            assertFalse(polled.active)
            assertEquals(80_000_000L, polled.peaks[AudioReceivePeak.WORKER_GAP_NS])
            assertEquals(polled, diagnostics.take(true))
        } finally {
            release.countDown()
            ending.join(2_000)
        }
        assertFalse(ending.isAlive)
    }

    private fun observer(stopped: (AudioReceiveDiagnostics) -> Unit) =
        object : AudioDiagnosticsObserver {
            override fun onEvent(
                kind: AudioDiagnosticEventKind,
                value: Long,
            ) = Unit

            override fun onStopped(snapshot: AudioReceiveDiagnostics) = stopped(snapshot)
        }

    private fun packet(
        client: Int,
        sequence: Int,
        arrival: Long,
    ) = QueuedFrame(VoiceFrame(client, sequence, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), arrival)
}
