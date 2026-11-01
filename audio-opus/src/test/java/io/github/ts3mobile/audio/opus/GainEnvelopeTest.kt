package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GainEnvelopeTest {
    @Test fun initialSettingIsImmediateAndChangesReachTargetInTwentyMilliseconds() {
        for (sampleRate in listOf(24_000, 48_000)) {
            val envelope = GainEnvelope(sampleRate)
            envelope.setTarget(0.5f)
            assertEquals(0.5f, envelope.next(), 0f)
            envelope.setTarget(2f)
            val ramp = FloatArray(sampleRate / 50) { envelope.next() }
            assertTrue(ramp.first() > 0.5f && ramp.first() < 0.51f)
            assertTrue(ramp.toList().zipWithNext().all { (before, after) -> after >= before && after - before < 0.004f })
            assertEquals(2f, ramp.last(), 0f)
            assertEquals(2f, envelope.next(), 0f)
        }
    }

    @Test fun repeatedTargetDoesNotRestartRampAndMidRampChangesStayContinuous() {
        val envelope = GainEnvelope()
        envelope.setTarget(1f)
        envelope.setTarget(2f)
        repeat(480) { envelope.next() }
        envelope.setTarget(2f)
        repeat(480) { envelope.next() }
        assertEquals(2f, envelope.next(), 0f)
        envelope.setTarget(0.5f)
        repeat(480) { envelope.next() }
        val before = envelope.next()
        envelope.setTarget(1f)
        assertEquals(before, envelope.next(), 0.002f)
        repeat(959) { envelope.next() }
        assertEquals(1f, envelope.next(), 0f)
    }

    @Test fun muteIsImmediateAndUnmuteRampsFromZero() {
        val envelope = GainEnvelope()
        envelope.setTarget(2f)
        envelope.setTarget(0f)
        assertEquals(0f, envelope.next(), 0f)
        envelope.setTarget(1f)
        assertTrue(envelope.next() < 0.002f)
        repeat(959) { envelope.next() }
        assertEquals(1f, envelope.next(), 0f)
    }

    @Test fun invalidGainsFailExplicitly() {
        val envelope = GainEnvelope()
        for (gain in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2.1f)) {
            assertThrows(IllegalArgumentException::class.java) { envelope.setTarget(gain) }
        }
    }
}
