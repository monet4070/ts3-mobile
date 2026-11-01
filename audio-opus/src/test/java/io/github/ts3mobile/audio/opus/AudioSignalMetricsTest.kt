package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class AudioSignalMetricsTest {
    @Test
    fun syntheticToneLevelsAndSpectrumAreMeasuredAcrossInputLevels() {
        listOf(-60.0, -40.0, -20.0, -6.0).forEach { peakDbfs ->
            val amplitude = 32_768 * 10.0.pow(peakDbfs / 20)
            val samples = ShortArray(4_800) { (amplitude * sin(2 * PI * 1_000 * it / 48_000)).toInt().toShort() }
            val levels = AudioSignalMetrics.levels(samples)
            assertEquals(peakDbfs, levels.peakDbfs, 0.3)
            assertEquals(peakDbfs - 3.0103, levels.rmsDbfs, 0.3)
            assertEquals(0, levels.clippedSamples)
            assertEquals(amplitude, AudioSignalMetrics.toneAmplitude(samples, 1_000.0), 1.0)
            assertTrue(AudioSignalMetrics.toneAmplitude(samples, 3_000.0) < 1.0)
        }
    }

    @Test
    fun silenceAndBothPcmRailsAreReportedWithoutInventingAnInputLevel() {
        val silence = AudioSignalMetrics.levels(ShortArray(480))
        assertEquals(Double.NEGATIVE_INFINITY, silence.rmsDbfs, 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, silence.peakDbfs, 0.0)
        val rails = AudioSignalMetrics.levels(shortArrayOf(-32_768, -32_767, 32_766, 32_767))
        assertEquals(2, rails.clippedSamples)
        assertEquals(0.0, rails.peakDbfs, 0.0)
    }

    @Test
    fun deterministicDelayAlignmentFindsPositiveAndNegativeOffsets() {
        val random = Random(24)
        val reference = ShortArray(4_800) { random.nextInt(-8_000, 8_001).toShort() }
        val delayed = ShortArray(31) + reference
        assertEquals(31, AudioSignalMetrics.delaySamples(reference, delayed, 64))
        assertEquals(-31, AudioSignalMetrics.delaySamples(delayed, reference, 64))
    }
}
