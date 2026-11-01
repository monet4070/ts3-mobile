package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class PcmLimiterTest {
    @Test fun lowLevelPcmIsBitExactWithNoDelayOrInventedTail() {
        val random = Random(24)
        val input = ShortArray(4_800) { random.nextInt(-5_000, 5_001).toShort() }
        val limiter = PcmLimiter()
        val output = limiter.process(FloatArray(input.size) { input[it].toFloat() })
        assertArrayEquals(input, output)
        assertEquals(0, AudioSignalMetrics.delaySamples(input, output, 240))
        assertEquals(0.0, AudioSignalMetrics.levels(output).rmsDbfs - AudioSignalMetrics.levels(input).rmsDbfs, 0.0001)
        assertArrayEquals(ShortArray(0), limiter.process(floatArrayOf()))
        assertArrayEquals(ShortArray(480), limiter.process(FloatArray(480)))
    }

    @Test fun bothPolaritiesAndEightCorrelatedBoostedTalkersStayBelowCeiling() {
        val output = PcmLimiter().process(FloatArray(960) { if (it % 2 == 0) 32767f * 16 else -32768f * 16 })
        assertTrue(output.all { abs(it.toInt()) <= PcmLimiter.CEILING.toInt() })
        assertTrue(output.first() > 0 && output[1] < 0)
        val levels = AudioSignalMetrics.levels(output)
        assertEquals(0, levels.clippedSamples)
        assertTrue(levels.peakDbfs <= -1.0 && levels.peakDbfs > -1.01)
    }

    @Test fun releaseUsesSampleRateAndResetDropsAttenuationWithoutRetainingAudio() {
        for (sampleRate in listOf(24_000, 48_000)) {
            val limiter = PcmLimiter(sampleRate)
            limiter.process(floatArrayOf(PcmLimiter.CEILING * 2))
            val held = limiter.process(FloatArray(sampleRate / 200) { 1_000f })
            assertTrue(held.all { it.toInt() == 500 })
            val released = limiter.process(FloatArray(sampleRate * 80 / 1000) { 1_000f })
            assertEquals(816.0, released.last().toDouble(), 2.0)
            assertTrue(released.toList().zipWithNext().all { (before, after) -> after >= before && after - before <= 1 })
            limiter.reset()
            assertArrayEquals(shortArrayOf(1_000), limiter.process(floatArrayOf(1_000f)))
        }
    }

    @Test fun packetBoundariesDoNotChangeLimiterSamplesOrTailLength() {
        val input = FloatArray(2_400) { if (it == 479) 65_536f else (it % 1_000).toFloat() }
        val expected = PcmLimiter().process(input)
        val limiter = PcmLimiter()
        val actual = input.toList().chunked(120).flatMap { limiter.process(it.toFloatArray()).toList() }.toShortArray()
        assertArrayEquals(expected, actual)
        assertEquals(input.size, actual.size)
    }

    @Test fun steadyOverdrivenToneHasLessHarmonicDistortionThanIntegerClipping() {
        for (frequency in listOf(100.0, 1_000.0)) {
            val input = FloatArray(48_000) { (sin(2 * PI * frequency * it / 48_000) * 60_000).toFloat() }
            val limited = PcmLimiter().process(input).copyOfRange(24_000, 48_000)
            val clipped = ShortArray(input.size) { input[it].toInt().coerceIn(-32768, 32767).toShort() }.copyOfRange(24_000, 48_000)
            val limitedDistortion = harmonicDistortion(limited, frequency)
            val clippedDistortion = harmonicDistortion(clipped, frequency)
            println("A2 synthetic $frequency Hz: limiter THD=$limitedDistortion, integer clipping THD=$clippedDistortion")
            assertTrue("$frequency Hz limiter THD=$limitedDistortion, clipping THD=$clippedDistortion", limitedDistortion < 0.01)
            assertTrue(limitedDistortion < clippedDistortion / 10)
        }
    }

    @Test fun nonFiniteInputFailsAtTheDspBoundary() {
        assertThrows(IllegalArgumentException::class.java) { PcmLimiter().process(floatArrayOf(Float.NaN)) }
    }

    @Test fun quietSignalRecoversUnityAfterAnOverloadReleases() {
        val limiter = PcmLimiter()
        limiter.process(floatArrayOf(65536f))
        limiter.process(FloatArray(96_000))
        assertArrayEquals(shortArrayOf(12345, -12345, 1, -1), limiter.process(floatArrayOf(12345f, -12345f, 1f, -1f)))
    }

    private fun harmonicDistortion(
        samples: ShortArray,
        frequency: Double,
    ): Double {
        val fundamental = AudioSignalMetrics.toneAmplitude(samples, frequency)
        val harmonics =
            (2..10).sumOf { order ->
                val amplitude = AudioSignalMetrics.toneAmplitude(samples, frequency * order)
                amplitude * amplitude
            }
        return sqrt(harmonics) / fundamental
    }
}
