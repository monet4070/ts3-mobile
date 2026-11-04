package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivePcmBufferTest {
    @Test fun explicitCapacityDropsWholeOldChunksAndReleasesTheirLeases() {
        val stats = ReceiveStats()
        val pcm = ReceivePcmBuffer(stats)
        val firstBudget = ReceiveMediaBudget()
        val first = firstBudget.reserve(1, 5760, 1, 0)!!
        pcm.add(ShortArray(5760) { 1 }, first, 0)
        repeat(2) { index ->
            val budget = ReceiveMediaBudget()
            pcm.add(ShortArray(5760) { (index + 2).toShort() }, budget.reserve(1, 5760, 1, 0)!!, 0)
        }
        assertEquals(ReceivePolicy.PCM_FIFO_CAPACITY_SAMPLES, pcm.available)
        assertEquals(5760L, stats.overflowDroppedPcmSamples.get())
        assertEquals(0, firstBudget.samples(1))
        assertArrayEquals(ShortArray(5760) { 2 } + ShortArray(5760) { 3 }, pcm.read(20000, 0))
    }

    @Test fun shortBlocksJoinWithoutRepeatingOrSkippingValidTailSamples() {
        listOf(120, 240, 479, 480).forEach { tail ->
            val budget = ReceiveMediaBudget()
            val pcm = ReceivePcmBuffer(budget.stats)
            val first = ShortArray(tail) { (it + 1).toShort() }
            val second = ShortArray(480) { (it + 1000).toShort() }
            pcm.add(first, budget.reserve(1, tail, 10, 0)!!, 0)
            pcm.add(second, budget.reserve(1, 480, 10, 0)!!, 0)
            assertArrayEquals(first + second, pcm.read(480, 0) + pcm.read(480, 0))
            assertEquals(0, budget.samples(1))
        }
    }

    @Test fun budgetInvalidationDropsOnlyInvalidChunksWithoutReplayingOldAudio() {
        val budget = ReceiveMediaBudget()
        val pcm = ReceivePcmBuffer(budget.stats)
        pcm.add(ShortArray(5760) { 1 }, budget.reserve(1, 5760, 10, 0)!!, 0)
        budget.invalidate(1)
        pcm.add(ShortArray(120) { 2 }, budget.reserve(1, 120, 10, 0)!!, 0)
        assertArrayEquals(ShortArray(120) { 2 }, pcm.read(10000, 0))
        assertEquals(0, budget.samples(1))
    }

    @Test fun readsReleaseSampleBudgetIncrementallyAndClearReleasesTheRemainingTail() {
        val budget = ReceiveMediaBudget()
        val pcm = ReceivePcmBuffer(budget.stats)
        pcm.add(ShortArray(960), budget.reserve(1, 960, 100, 0)!!, 0)
        pcm.read(480, 0)
        assertEquals(480, budget.samples(1))
        assertEquals(480, pcm.clear())
        assertEquals(0, budget.samples(1))
    }

    @Test fun ageMetricsKeepInitialArrivalAcrossQueueTransferAndPcmReads() {
        val budget = ReceiveMediaBudget()
        val pcm = ReceivePcmBuffer(budget.stats)
        pcm.add(ShortArray(480), budget.reserve(1, 480, 10, 10_000_000)!!, 30_000_000)
        pcm.read(480, 90_000_000)
        assertEquals(60_000_000L, budget.stats.maxTalkerAgeNanos.get())
        assertEquals(80_000_000L, budget.stats.maxCombinedWaitNanos.get())
    }
}
