package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderReferenceBufferTest {
    @Test fun crossThreadInvalidationBetweenPartialWritesCannotPublishOldAssemblyAsNew() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        reference.start()
        val partialWritten = java.util.concurrent.CountDownLatch(1)
        val invalidated = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val writer =
                executor.submit {
                    reference.accepted(ShortArray(240) { 1000 }, 0, 240, 0, 0, RenderTiming(0, AudioTimingQuality.MEASURED))
                    partialWritten.countDown()
                    check(invalidated.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    reference.accepted(ShortArray(480) { 2000 }, 0, 480, 240, 0, RenderTiming(0, AudioTimingQuality.MEASURED))
                }
            assertTrue(partialWritten.await(3, java.util.concurrent.TimeUnit.SECONDS))
            reference.invalidate()
            invalidated.countDown()
            writer.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val block = reference.take(0).blocks.single()
            assertEquals(reference.generation, block.generation)
            assertArrayEquals(FloatArray(480) { 2000 / 32768f }, block.pcm, 0f)
        } finally {
            invalidated.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun onlyAcceptedPrefixesBecomeIndependentCompleteReferenceBlocks() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        val output = FakePlaybackOutput().apply { writeResults.addAll(listOf(120, 0, 360, 0)) }
        val timeline = PlaybackTimeline(output, reference)
        timeline.start(0)
        val input = ShortArray(960) { (it + 1).toShort() }
        timeline.tick(input, 0)
        assertTrue(reference.take(0).blocks.isEmpty())
        timeline.tick(null, 10_000_000)
        val first = reference.take(10_000_000).blocks.single()
        assertEquals(0L, first.firstFrame)
        assertArrayEquals(FloatArray(480) { input[it] / 32768f }, first.pcm, 0f)
        timeline.tick(null, 20_000_000)
        val second = reference.take(20_000_000).blocks.single()
        assertEquals(480L, second.firstFrame)
        assertArrayEquals(FloatArray(480) { input[it + 480] / 32768f }, second.pcm, 0f)
        input.fill(0)
        assertTrue(first.pcm.all { it > 0f })
        assertTrue(reference.take(20_000_000).blocks.isEmpty())
    }

    @Test fun failedAndUnwrittenSamplesNeverReachReferenceAndShortTailsAreNotInvented() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        val output = FakePlaybackOutput().apply { maxWriteSamples = 0 }
        val timeline = PlaybackTimeline(output, reference)
        timeline.start(0)
        timeline.tick(ShortArray(480) { 1 }, 0)
        assertTrue(reference.take(0).blocks.isEmpty())
        output.writeResults.add(-6)
        assertThrows(IllegalStateException::class.java) { timeline.tick(null, 1) }
        assertTrue(reference.take(1).blocks.isEmpty())
        timeline.discard()
        timeline.start(2)
        output.maxWriteSamples = 120
        timeline.tick(ShortArray(120) { 1 }, 2)
        assertTrue(reference.take(2).blocks.isEmpty())
        timeline.discard()
        assertFalse(reference.take(2).playing)
    }

    @Test fun referenceMatchesFinalParticipantGainAndLimiterOutputExactly() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        val output = FakePlaybackOutput()
        val timeline = PlaybackTimeline(output, reference)
        val limited = ParticipantGainMixer().mix(listOf(ParticipantGainMixer.Input(1, ShortArray(480) { 30000 }, 2f)), 480)
        timeline.start(0)
        timeline.tick(limited, 0)
        assertTrue(output.accepted.all { it.toInt() == PcmLimiter.CEILING.toInt() })
        assertArrayEquals(FloatArray(480) { output.accepted[it] / 32768f }, reference.take(0).blocks.single().pcm, 0f)
    }

    @Test fun inactiveCaptureDoesNotAccumulateAndRestartDropsOldPartialAndQueue() {
        val reference = RenderReferenceBuffer()
        reference.start()
        repeat(100) { offer(reference, it * 480L) }
        reference.setCaptureActive(true)
        assertTrue(reference.take(0).blocks.isEmpty())
        offer(reference, 0, 120)
        val oldEpoch = reference.generation
        reference.discard()
        reference.start()
        offer(reference, 0)
        val block = reference.take(0).blocks.single()
        assertTrue(block.generation > oldEpoch)
        assertEquals(0L, block.firstFrame)
        assertEquals(480, block.pcm.size)
        reference.setCaptureActive(false)
        assertTrue(reference.take(0).blocks.isEmpty())
    }

    @Test fun overflowAndPerTickBudgetInvalidateRatherThanDrainOldHistory() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        reference.start()
        val initial = reference.generation
        repeat(51) { offer(reference, it * 480L) }
        assertTrue(reference.generation > initial)
        assertTrue(reference.take(0).blocks.isEmpty())
        repeat(6) { offer(reference, (51 + it) * 480L) }
        val overBudget = reference.take(0)
        assertTrue(overBudget.overBudget)
        assertTrue(overBudget.blocks.isEmpty())
        repeat(5) { offer(reference, (57 + it) * 480L) }
        assertEquals(5, reference.take(0).blocks.size)
    }

    @Test fun staleOrNonContiguousReferencesInvalidateTheirGeneration() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        reference.start()
        offer(reference, 0)
        assertTrue(reference.take(500_000_001).overBudget)
        offer(reference, 480, 120)
        val beforeGap = reference.generation
        offer(reference, 960)
        assertTrue(reference.generation > beforeGap)
        assertEquals(960L, reference.take(0).blocks.single().firstFrame)
    }

    @Test fun validAudioTrackClockMapsAcceptedSamplesAndMissingClockIsExplicitlyEstimated() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        val output = FakePlaybackOutput().apply { timestamp = AudioSampleClock(0, 0) }
        val timeline = PlaybackTimeline(output, reference)
        timeline.start(0)
        timeline.tick(ShortArray(480), 0)
        val measured = reference.take(0).blocks.single()
        assertEquals(AudioTimingQuality.MEASURED, measured.timing.quality)
        assertEquals(0L, measured.timing.presentationNanos)
        output.timestamp = null
        timeline.tick(ShortArray(480), 10_000_000)
        val estimated = reference.take(10_000_000).blocks.single()
        assertEquals(AudioTimingQuality.ESTIMATED, estimated.timing.quality)
        assertEquals(20_000_000L, estimated.timing.presentationNanos)
    }

    @Test fun timestampHeadWrapKeepsTheReferenceSampleOriginAndDoesNotReusePreStartClocks() {
        val reference = RenderReferenceBuffer()
        reference.setCaptureActive(true)
        val output =
            FakePlaybackOutput().apply {
                playbackHead = 0xffff_fff0L
                timestamp = AudioSampleClock(0xffff_fff0L, 1_000_000L)
            }
        val timeline = PlaybackTimeline(output, reference)
        timeline.start(1_000_000L)
        timeline.tick(ShortArray(480), 1_000_000L)
        assertEquals(AudioTimingQuality.MEASURED, reference.take(1_000_000L).blocks.single().timing.quality)
        output.timestamp = AudioSampleClock(0x10, 2_000_000L)
        timeline.tick(ShortArray(480), 2_000_000L)
        assertEquals(2_000_000L + 448 * 1_000_000_000L / 48_000, reference.take(2_000_000L).blocks.single().timing.presentationNanos)
        timeline.discard()
        timeline.start(10_000_000L)
        timeline.tick(ShortArray(480), 10_000_000L)
        assertEquals(AudioTimingQuality.ESTIMATED, reference.take(10_000_000L).blocks.single().timing.quality)
    }

    private fun offer(
        reference: RenderReferenceBuffer,
        frame: Long,
        count: Int = 480,
    ) {
        reference.accepted(ShortArray(count) { 1234 }, 0, count, frame, 0, RenderTiming(0, AudioTimingQuality.MEASURED))
    }
}
