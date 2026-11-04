package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketDistanceTest {
    @Test
    fun signedPacketDistanceHandlesSixteenBitWraparound() {
        assertEquals(1, signedPacketDistance16(65_535, 0))
        assertEquals(3, signedPacketDistance16(65_534, 1))
    }

    @Test
    fun signedPacketDistanceIdentifiesDuplicateAndOlderPacket() {
        assertEquals(0, signedPacketDistance16(12, 12))
        assertEquals(-1, signedPacketDistance16(12, 11))
        assertEquals(-1, signedPacketDistance16(0, 65_535))
    }
}
