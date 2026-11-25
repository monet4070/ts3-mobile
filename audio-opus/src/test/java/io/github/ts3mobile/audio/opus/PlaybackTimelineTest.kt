package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun firstWriteToHeadUsesAcceptedWriteAndEmitsOnlyOnce() {
        val output = FakePlaybackOutput()
        output.writeResults.addAll(listOf(0, 1))
        val stats = ReceiveStats()
        val delays = mutableListOf<Long>()
        stats.observer =
            object : AudioDiagnosticsObserver {
                override fun onEvent(
                    kind: AudioDiagnosticEventKind,
                    value: Long,
                ) {
                    if (kind == AudioDiagnosticEventKind.FIRST_WRITE_TO_HEAD_US) delays += value
                }

                override fun onStopped(snapshot: AudioReceiveDiagnostics) = Unit
            }
        var clock = 0L
        val timeline = PlaybackTimeline(output, receiveStats = stats, clockNanos = { clock })
        timeline.start(clock)
        timeline.tick(shortArrayOf(7), clock)
        clock = TICK
        timeline.tick(null, clock)
        assertTrue(delays.isEmpty())
        output.playbackHead = 1L
        clock = 3 * TICK
        timeline.tick(null, clock)
        timeline.tick(null, 4 * TICK)
        assertEquals(listOf(20_000L), delays)
    }

    @Test
    fun temporaryStarvationPreservesSubmittedTailAndResumesWithoutFlush() {
        listOf(10L, 20L, 40L).forEach { gapMs ->
            val output = FakePlaybackOutput()
            val timeline = PlaybackTimeline(output)
            timeline.start()
            timeline.tick(shortArrayOf(11, 22, 33), 0L)
            timeline.tick(null, TICK)
            timeline.tick(null, TICK + gapMs * MILLIS)
            assertEquals(PlaybackTimeline.State.RECOVERING, timeline.state)
            assertEquals(0, output.discardCount)
            timeline.tick(shortArrayOf(44), TICK + gapMs * MILLIS)
            assertEquals(PlaybackTimeline.State.PLAYING, timeline.state)
            assertArrayEquals(shortArrayOf(11, 22, 33, 44), output.accepted.toShortArray())
        }
    }

    @Test
    fun bluetoothResumesAfterShortStarvationWithoutRecreatingOrFillingTheOutput() {
        val output = FakePlaybackOutput().apply { routedKind = AudioRouteKind.BLUETOOTH }
        val timeline = PlaybackTimeline(output)
        timeline.start(0L)
        timeline.tick(shortArrayOf(11, 22), 0L)
        output.playbackHead = 2
        for (gapMs in 10L..380L step 10) timeline.tick(null, gapMs * MILLIS)

        assertEquals(PlaybackTimeline.State.RECOVERING, timeline.state)
        assertEquals(0, output.discardCount)
        assertEquals(1, output.writeCalls)
        timeline.tick(shortArrayOf(33, 44), 390 * MILLIS)

        assertEquals(PlaybackTimeline.State.PLAYING, timeline.state)
        assertEquals(2, output.writeCalls)
        assertArrayEquals(shortArrayOf(11, 22, 33, 44), output.accepted.toShortArray())
    }

    @Test
    fun bluetoothDrainsAfterTheBoundedGapWithoutAppendingSilence() {
        val output = FakePlaybackOutput().apply { routedKind = AudioRouteKind.BLUETOOTH }
        val timeline = PlaybackTimeline(output)
        timeline.start(0L)
        timeline.tick(shortArrayOf(11), 0L)
        output.playbackHead = 1
        timeline.tick(null, TICK)
        timeline.tick(null, TICK + 499 * MILLIS)
        assertEquals(0, output.discardCount)
        timeline.tick(null, TICK + 500 * MILLIS)

        assertEquals(PlaybackTimeline.State.IDLE, timeline.state)
        assertEquals(1, output.discardCount)
        assertEquals(1, output.writeCalls)
    }

    @Test
    fun naturalEndWaitsForAllAcceptedSamplesBeforeDiscarding() {
        val output = FakePlaybackOutput()
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(ShortArray(480) { 12 }, 0L)
        timeline.tick(null, TICK)
        output.playbackHead = 240L
        timeline.tick(null, TICK + PlaybackTimeline.RECOVERY_NANOS)
        assertEquals(PlaybackTimeline.State.DRAINING, timeline.state)
        assertEquals(0, output.discardCount)
        output.playbackHead = 479L
        timeline.tick(null, 80 * MILLIS)
        assertEquals(0, output.discardCount)
        output.playbackHead = 480L
        timeline.tick(null, 90 * MILLIS)
        assertEquals(PlaybackTimeline.State.IDLE, timeline.state)
        assertEquals(480L, timeline.playedFrames)
        assertEquals(1, output.discardCount)
        assertEquals(0L, timeline.drainTimeoutCount)
    }

    @Test
    fun stalledPlaybackTimesOutWithinFiveHundredMillisecondsWithoutAppendingSilence() {
        val output = FakePlaybackOutput()
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(ShortArray(480), 0L)
        timeline.tick(null, TICK)
        val drainAt = TICK + PlaybackTimeline.RECOVERY_NANOS
        timeline.tick(null, drainAt)
        timeline.tick(null, drainAt + PlaybackTimeline.MAX_DRAIN_NANOS)
        assertEquals(PlaybackTimeline.State.IDLE, timeline.state)
        assertEquals(1L, timeline.drainTimeoutCount)
        assertEquals(480L, timeline.submittedFrames)
        assertEquals(1, output.writeCalls)
    }

    @Test
    fun validSpeechDuringDrainResumesWithoutDiscarding() {
        val output = FakePlaybackOutput()
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(shortArrayOf(7), 0L)
        timeline.tick(null, TICK)
        timeline.tick(null, TICK + PlaybackTimeline.RECOVERY_NANOS)
        timeline.tick(shortArrayOf(8), 80 * MILLIS)
        assertEquals(PlaybackTimeline.State.PLAYING, timeline.state)
        assertEquals(0, output.discardCount)
        assertArrayEquals(shortArrayOf(7, 8), output.accepted.toShortArray())
    }

    @Test
    fun partialAndZeroWritesCountOnlyAcceptedSamplesAndNeverReplayTheirPrefix() {
        val output = FakePlaybackOutput()
        output.writeResults.addAll(listOf(2, 0))
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(shortArrayOf(1, 2, 3, 4, 5), 0L)
        assertEquals(2L, timeline.submittedFrames)
        assertFalse(timeline.acceptsPcm)
        timeline.tick(null, TICK)
        assertEquals(5L, timeline.submittedFrames)
        assertTrue(timeline.acceptsPcm)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), output.accepted.toShortArray())
    }

    @Test
    fun repeatedZeroWritesHaveADeadlineAndDoNotBusySpin() {
        val output = FakePlaybackOutput().apply { maxWriteSamples = 0 }
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(shortArrayOf(1), 0L)
        repeat(9) { index -> timeline.tick(null, (index + 1) * TICK) }
        assertEquals(10, output.writeCalls)
        assertEquals(0L, timeline.submittedFrames)
        assertThrows(IllegalStateException::class.java) {
            timeline.tick(null, PlaybackTimeline.WRITE_TIMEOUT_NANOS)
        }
        assertEquals(11, output.writeCalls)
    }

    @Test fun lateWakeWithHeadProgressWritesOnlyTheFreshUnsubmittedSuffix() {
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(2, 0)) }
        val timeline = PlaybackTimeline(output)
        timeline.start(0L)
        timeline.tick(shortArrayOf(1, 2, 3, 4), 0L, 0L)
        output.playbackHead = 2
        timeline.tick(null, 110 * MILLIS)
        assertEquals(listOf<Short>(1, 2, 3, 4), output.accepted)
        assertEquals(2L, timeline.playedFrames)
        assertTrue(timeline.acceptsPcm)
    }

    @Test fun expiredSuffixIsNeverUsedToProbeAnOtherwiseHealthyOutput() {
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(2, 0)) }
        val stats = ReceiveStats()
        val timeline = PlaybackTimeline(output, receiveStats = stats)
        val mixedAt = PlaybackTimeline.MAX_PCM_AGE_NANOS - 100 * MILLIS
        timeline.start(mixedAt)
        timeline.tick(shortArrayOf(1, 2, 3, 4), mixedAt, 0L)
        timeline.tick(null, mixedAt + 110 * MILLIS)
        assertEquals(2, output.writeCalls)
        assertEquals(2L, stats.counters()[AudioReceiveCounter.OUTPUT_EXPIRED_PCM_SAMPLES])
        assertTrue(timeline.acceptsPcm)
        val resumedAt = mixedAt + 120 * MILLIS
        timeline.tick(shortArrayOf(5, 6), resumedAt, resumedAt)
        assertEquals(listOf<Short>(1, 2, 5, 6), output.accepted)
    }

    @Test fun droppingExpiredMediaDoesNotResetTheBudgetForAnOutputThatStillCannotWrite() {
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(2, 0)) }
        val timeline = PlaybackTimeline(output)
        val mixedAt = PlaybackTimeline.MAX_PCM_AGE_NANOS - 100 * MILLIS
        timeline.start(mixedAt)
        timeline.tick(shortArrayOf(1, 2, 3, 4), mixedAt, 0L)
        output.maxWriteSamples = 0
        timeline.tick(null, mixedAt + 110 * MILLIS)
        val resumedAt = mixedAt + 120 * MILLIS
        assertThrows(IllegalStateException::class.java) {
            timeline.tick(shortArrayOf(5, 6), resumedAt, resumedAt)
        }
        assertEquals(3, output.writeCalls)
    }

    @Test
    fun coldBluetoothKeepsPendingSpeechUntilConsumptionThenUsesNormalDeadline() {
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(2, 0)) }
        val timeline = PlaybackTimeline(output.apply { routedKind = AudioRouteKind.BLUETOOTH })
        timeline.start(0L)
        timeline.tick(shortArrayOf(1, 2, 3), 0L)
        output.maxWriteSamples = 0
        for (elapsed in 10L..1110L step 10) timeline.tick(null, elapsed * MILLIS)
        assertEquals(listOf<Short>(1, 2), output.accepted)
        assertEquals(0L, timeline.playedFrames)
        assertEquals(0, output.discardCount)

        output.playbackHead = 2L
        val readyAt = 1120 * MILLIS
        timeline.tick(null, readyAt)
        timeline.tick(null, readyAt + PlaybackTimeline.WRITE_TIMEOUT_NANOS - TICK)
        assertThrows(IllegalStateException::class.java) {
            timeline.tick(null, readyAt + PlaybackTimeline.WRITE_TIMEOUT_NANOS)
        }
    }

    @Test
    fun coldBluetoothContinuesThePartialWriteWithoutRepeatingItsPrefix() {
        val output =
            FakePlaybackOutput().apply {
                routedKind = AudioRouteKind.BLUETOOTH
                writeResults.addAll(listOf(2, 0))
            }
        val timeline = PlaybackTimeline(output)
        timeline.start(0L)
        timeline.tick(shortArrayOf(1, 2, 3), 0L)
        output.maxWriteSamples = 0
        timeline.tick(null, 1110 * MILLIS)
        output.playbackHead = 2L
        output.maxWriteSamples = Int.MAX_VALUE
        timeline.tick(null, 1120 * MILLIS)
        assertEquals(2L, timeline.playedFrames)
        assertEquals(listOf<Short>(1, 2, 3), output.accepted)
        assertTrue(timeline.acceptsPcm)
    }

    @Test
    fun bluetoothThatNeverConsumesStillTimesOutAtStartupDeadline() {
        val output =
            FakePlaybackOutput().apply {
                routedKind = AudioRouteKind.BLUETOOTH
                writeResults.addAll(listOf(1, 0))
            }
        val timeline = PlaybackTimeline(output)
        timeline.start(0L)
        timeline.tick(shortArrayOf(1, 2), 0L)
        output.maxWriteSamples = 0
        val deadline = PlaybackTimeline.BLUETOOTH_STARTUP_NANOS
        timeline.tick(null, deadline - TICK)
        assertThrows(IllegalStateException::class.java) { timeline.tick(null, deadline) }
    }

    @Test
    fun shortBluetoothOpeningIsNotFlushedBeforeTheColdStreamStarts() {
        val output = FakePlaybackOutput().apply { routedKind = AudioRouteKind.BLUETOOTH }
        val timeline = PlaybackTimeline(output)
        timeline.start(0L)
        timeline.tick(shortArrayOf(11, 22), 0L)
        timeline.tick(null, TICK)
        timeline.tick(null, 1110 * MILLIS)

        assertEquals(PlaybackTimeline.State.RECOVERING, timeline.state)
        assertEquals(0, output.discardCount)
        output.playbackHead = 2L
        timeline.tick(null, 1120 * MILLIS)
        assertEquals(PlaybackTimeline.State.IDLE, timeline.state)
        assertEquals(0L, timeline.drainTimeoutCount)
    }

    @Test
    fun writeErrorsAndImpossibleCountsAreNotReportedAsSuccess() {
        listOf(-6, 2).forEach { result ->
            val output = FakePlaybackOutput().apply { writeResults.addLast(result) }
            val timeline = PlaybackTimeline(output)
            timeline.start()
            assertThrows(IllegalStateException::class.java) { timeline.tick(shortArrayOf(1), 0L) }
            assertEquals(0L, timeline.submittedFrames)
        }
    }

    @Test
    fun unsignedPlaybackHeadWrapDoesNotLoseTailFrames() {
        val output = FakePlaybackOutput().apply { playbackHead = 0xffff_fff0L }
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(ShortArray(32), 0L)
        output.playbackHead = 0x10L
        timeline.tick(null, TICK)
        assertEquals(32L, timeline.playedFrames)
        timeline.tick(null, TICK + PlaybackTimeline.RECOVERY_NANOS)
        assertEquals(PlaybackTimeline.State.IDLE, timeline.state)
    }

    @Test
    fun backwardsHeadDoesNotDeclareTailPlayed() {
        val output = FakePlaybackOutput().apply { playbackHead = 10L }
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(ShortArray(480), 0L)
        output.playbackHead = 9L
        timeline.tick(null, TICK)
        assertEquals(0L, timeline.playedFrames)
        output.playbackHead = 250L
        timeline.tick(null, 2 * TICK)
        assertEquals(240L, timeline.playedFrames)
    }

    @Test
    fun forcedDiscardRetiresPartialWriteAndResetsTheNextOutputOrigin() {
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(1, 0)) }
        val timeline = PlaybackTimeline(output)
        timeline.start()
        timeline.tick(shortArrayOf(11, 12, 13), 0L)
        timeline.discard()
        timeline.tick(shortArrayOf(99), TICK)
        assertEquals(listOf<Short>(11), output.accepted)
        timeline.start()
        timeline.tick(shortArrayOf(21, 22), 2 * TICK)
        assertEquals(0L, timeline.playedFrames)
        assertEquals(2L, timeline.submittedFrames)
        assertEquals(listOf<Short>(11, 21, 22), output.accepted)
    }

    @Test fun combinedResidenceIncludesPartialWriteWaitingUntilActualOutputAcceptance() {
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(120, 0)) }
        val stats = ReceiveStats()
        val timeline = PlaybackTimeline(output, receiveStats = stats)
        timeline.start(0)
        timeline.tick(ShortArray(480), 60 * MILLIS, 10 * MILLIS)
        assertEquals(50 * MILLIS, stats.maxCombinedWaitNanos.get())
        timeline.tick(null, 90 * MILLIS)
        assertEquals(80 * MILLIS, stats.maxCombinedWaitNanos.get())
    }

    private companion object {
        const val MILLIS = 1_000_000L
        const val TICK = 10 * MILLIS
    }
}
