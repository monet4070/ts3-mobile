package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTimingCollectorTest {
    @Test fun windowUsesNearestRankUpperBoundsAndKeepsSendSpacingAcrossSamples() {
        val collector = VoiceTimingCollector()
        assertNull(collector.takeWindow())
        val token = collector.open(true)
        repeat(18) { collector.received(token, 5_000_000, 5_250_000) }
        collector.received(token, 5_000_000, 6_250_000)
        collector.received(token, 5_000_000, 1_005_000_001)
        collector.received(token, 5_000_000, 4_999_999)
        collector.sent(token, 8_000_000, 10_000_000)
        collector.emptyRead(token)
        val first = checkNotNull(collector.takeWindow(3))
        assertTrue(first.udpTimestampsAvailable)
        assertEquals(20L, first.udpReadToCallback.buckets.sum())
        assertEquals(500L, first.udpReadToCallback.p50UpperUs)
        assertEquals(2_000L, first.udpReadToCallback.p95UpperUs)
        assertEquals(1L, first.udpReadToCallback.buckets.last())
        assertEquals(2_000L, first.encodedToUdpSent.p95UpperUs)
        assertEquals(1L, first.emptyUplinkReads)
        assertEquals(3L, first.captureOverflowFrames)
        collector.sent(token, null, 19_999_999)
        collector.sent(token, null, 29_999_999)
        val second = checkNotNull(collector.takeWindow())
        assertEquals(1L, second.sendGapsBelow10Ms)
        assertNull(second.encodedToUdpSent.p95UpperUs)
        assertEquals(0L, second.emptyUplinkReads)
    }

    @Test fun unavailableAndRetiredSessionsCannotFabricateDelays() {
        val collector = VoiceTimingCollector()
        val retired = collector.open(true)
        val current = collector.open(false)
        collector.received(retired, 0, 5_000)
        collector.sent(current, 0, 5_000)
        collector.emptyRead(retired)
        collector.emptyRead(current)
        collector.close(retired)
        collector.emptyRead(current)
        collector.close(current)
        collector.emptyRead(current)
        val result = checkNotNull(collector.takeWindow())
        assertFalse(result.udpTimestampsAvailable)
        assertEquals(2L, result.emptyUplinkReads)
        assertEquals(0L, result.udpReadToCallback.buckets.sum())
        assertEquals(0L, result.encodedToUdpSent.buckets.sum())
    }

    @Test fun aboveBoundQuantilesAreUnknownAndHistogramCountsAreBounded() {
        val collector = VoiceTimingCollector()
        val token = collector.open(true)
        repeat(11_000) { collector.received(token, 0, 1_000_000_001) }
        val delay = checkNotNull(collector.takeWindow()).udpReadToCallback
        assertEquals(VoiceTimingCollector.MAX_BUCKET_COUNT, delay.buckets.last())
        assertNull(delay.p50UpperUs)
        assertNull(delay.p95UpperUs)
    }
}
