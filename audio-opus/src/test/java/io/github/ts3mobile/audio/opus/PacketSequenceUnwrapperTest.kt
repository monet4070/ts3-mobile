package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketSequenceUnwrapperTest {
    @Test
    fun extendsPacketIdsAcrossSixteenBitWraparound() {
        val unwrapper = PacketSequenceUnwrapper()

        assertEquals(65_534L, unwrapper.unwrap(65_534))
        assertEquals(65_535L, unwrapper.unwrap(65_535))
        assertEquals(65_536L, unwrapper.unwrap(0))
        assertEquals(65_537L, unwrapper.unwrap(1))
    }

    @Test
    fun acceptsLimitedReorderingWithoutMovingTheHighestSequenceBackwards() {
        val unwrapper = PacketSequenceUnwrapper()

        assertEquals(100L, unwrapper.unwrap(100))
        assertEquals(102L, unwrapper.unwrap(102))
        assertEquals(101L, unwrapper.unwrap(101))
        assertEquals(103L, unwrapper.unwrap(103))
    }

    @Test
    fun remainsMonotonicAcrossAHighVolumePacketSoak() {
        val unwrapper = PacketSequenceUnwrapper()
        val start = 65_000

        repeat(200_000) { offset ->
            val rawPacketId = (start + offset) and 0xffff
            assertEquals(start.toLong() + offset, unwrapper.unwrap(rawPacketId))
        }
    }

    @Test
    fun resetStartsANewIndependentSequence() {
        val unwrapper = PacketSequenceUnwrapper()
        unwrapper.unwrap(65_535)
        unwrapper.unwrap(0)

        unwrapper.reset()

        assertEquals(42L, unwrapper.unwrap(42))
    }
}
