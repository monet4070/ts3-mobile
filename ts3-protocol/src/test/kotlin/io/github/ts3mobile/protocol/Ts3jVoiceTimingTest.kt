package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.protocol.NetworkPacket
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.header.ClientPacketHeader
import com.github.manevolent.ts3j.protocol.packet.PacketBody
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.nio.ByteBuffer

class Ts3jVoiceTimingTest {
    @Test fun installedDependencyIsDetectedAndOnlyProvidedFramesContributeToSendTiming() {
        val collector = VoiceTimingCollector()
        val timing = Ts3jVoiceTiming(collector, clock = { 8_000_000L })
        val enabled = System.getProperty("ts3j.voiceTimingExpected").toBoolean()
        val bytes = byteArrayOf(1)
        val outbound = NetworkPacket(DatagramPacket(bytes, bytes.size), ClientPacketHeader(), ByteBuffer.wrap(bytes))
        val incoming = PacketBody0Voice(ProtocolRole.SERVER)
        if (enabled) {
            PacketBody::class.java.getMethod("setUdpReceivedNanoTime", Long::class.javaObjectType).invoke(incoming, 4_000_000L)
            NetworkPacket::class.java.getMethod("markSent", Long::class.javaPrimitiveType).invoke(outbound, 10_000_000L)
        }
        timing.received(incoming)
        timing.offer(TimedEncodedVoiceFrame(bytes, 8_000_000L))
        timing.successfulSend(outbound)
        timing.successfulSend(outbound)
        timing.offer(TimedEncodedVoiceFrame(bytes, 8_000_000L))
        timing.clearOffered()
        timing.successfulSend(outbound)
        timing.offer(null)
        val window = checkNotNull(collector.takeWindow())
        assertEquals(enabled, window.udpTimestampsAvailable)
        assertEquals(1L, window.emptyUplinkReads)
        assertEquals(0L, window.sendGapsBelow10Ms)
        if (enabled) {
            assertEquals(1L, window.encodedToUdpSent.buckets.sum())
            assertEquals(2_000L, window.encodedToUdpSent.p95UpperUs)
            assertEquals(5_000L, window.udpReadToCallback.p95UpperUs)
        } else {
            assertNull(window.encodedToUdpSent.p95UpperUs)
            assertNull(window.udpReadToCallback.p95UpperUs)
        }
    }
}
