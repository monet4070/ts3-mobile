package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionStatsTest {
    @Test
    fun tracksNonZeroPcmAndProducesSummaryOnlyWhenEncoded() {
        val stats = CaptureSessionStats()

        // No frames encoded yet: no summary line.
        assertTrue(stats.summaryLine(encodedCount = 0, providedCount = 0).isEmpty())
        assertFalse(stats.capturedNonZero())

        stats.noteRawFrame(ShortArray(480))
        assertFalse(stats.capturedNonZero())

        stats.noteRawFrame(shortArrayOf(1, 0, -1) + ShortArray(477))
        assertTrue(stats.capturedNonZero())

        stats.noteDenoisedFrame(ShortArray(480), denoiseNanos = 50_000L, vadProbability = 0.25f)

        val summary = stats.summaryLine(encodedCount = 10, providedCount = 8)
        assertTrue(summary.startsWith("TS3_AUDIO: microphone session ended"))
        assertTrue(summary.contains("encoded=10"))
        assertTrue(summary.contains("provided=8"))
        assertTrue(summary.contains("nonZero=true"))
        assertTrue(summary.contains("rnnoiseAvg=50us"))
        assertTrue(summary.contains("vad=0.250"))
    }

    @Test
    fun averagesAcrossMultipleFrames() {
        val stats = CaptureSessionStats()
        stats.noteRawFrame(ShortArray(480))
        stats.noteDenoisedFrame(ShortArray(480), denoiseNanos = 20_000L, vadProbability = 0.0f)
        stats.noteRawFrame(ShortArray(480))
        stats.noteDenoisedFrame(ShortArray(480), denoiseNanos = 100_000L, vadProbability = 1.0f)

        val summary = stats.summaryLine(encodedCount = 2, providedCount = 2)
        // Average of 20us and 100us is 60us.
        assertTrue(summary.contains("rnnoiseAvg=60us"))
        // Max of 20us and 100us is 100us.
        assertTrue(summary.contains("rnnoiseMax=100us"))
        assertTrue(summary.contains("vad=0.500"))
    }
}
