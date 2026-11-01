package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeGateTest {
    @Test fun silenceAndShortImpulseDoNotOpen() {
        val gate = VolumeGate()
        repeat(100) { assertFalse(gate.update(-96.0, -40)) }
        assertFalse(gate.update(-20.0, -40))
        assertFalse(gate.update(-41.0, -40))
        assertFalse(gate.update(-20.0, -40))
    }

    @Test fun twoConsecutiveFramesAtThresholdOpen() {
        val gate = VolumeGate()
        assertFalse(gate.update(-40.0, -40))
        assertTrue(gate.update(-40.0, -40))
    }

    @Test fun hysteresisAndHoldAvoidChatteringAndPreserveTail() {
        val gate = opened()
        repeat(100) { assertTrue(gate.update(-44.0, -40)) }
        repeat(14) { assertTrue(gate.update(-47.0, -40)) }
        assertFalse(gate.update(-47.0, -40))
    }

    @Test fun renewedSoundResetsHold() {
        val gate = opened()
        repeat(14) { gate.update(-90.0, -40) }
        assertTrue(gate.update(-45.0, -40))
        repeat(14) { assertTrue(gate.update(-90.0, -40)) }
        assertFalse(gate.update(-90.0, -40))
    }

    @Test fun reopeningRequiresAttackAgain() {
        val gate = opened()
        repeat(15) { gate.update(-96.0, -40) }
        assertFalse(gate.update(-20.0, -40))
        assertTrue(gate.update(-20.0, -40))
    }

    @Test fun rmsUsesDbfsAndIncludesNegativeFullScaleWithoutOverflow() {
        assertEquals(-96.0, VolumeGate.rmsDb(FloatArray(960)), 0.0)
        assertEquals(0.0, VolumeGate.rmsDb(FloatArray(960) { -32768f }), 0.0)
        assertEquals(-6.0206, VolumeGate.rmsDb(FloatArray(960) { 16384f }), 0.0001)
        assertEquals(-6.0206, VolumeGate.rmsDb(FloatArray(960) { if (it % 2 == 0) 16384f else -16384f }), 0.0001)
    }

    @Test fun floatGateBranchRetainsSaturatedPcmThresholdMeaning() {
        val floating = floatArrayOf(0f, 1234.75f, -1234.75f, 65534f, -65536f)
        val saturated = floatArrayOf(0f, 1234f, -1234f, 32767f, -32768f)
        assertEquals(VolumeGate.rmsDb(saturated), VolumeGate.rmsDb(floating), 0.0)
    }

    private fun opened() = VolumeGate().apply { repeat(2) { update(-20.0, -40) } }
}
