package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.enums.CodecType
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.header.ServerPacketHeader
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class Ts3jVoiceFixtureTest {
    @Test
    fun emptyVoicePayloadRemainsTransportMetadataRatherThanDecodedAudio() {
        listOf(CodecType.OPUS_VOICE, CodecType.OPUS_MUSIC).forEach { codec ->
            val original = packet(codec, ByteArray(0))
            val decoded = roundTrip(original)
            assertEquals(65_535, decoded.packetId)
            assertEquals(7, decoded.clientId)
            assertEquals(codec, decoded.codecType)
            assertArrayEquals(ByteArray(0), decoded.codecData)
        }
    }

    @Test
    fun voiceAndMusicPreserveNonemptyPayloadWithoutGuessingDtxFromItsLength() {
        listOf(CodecType.OPUS_VOICE, CodecType.OPUS_MUSIC).forEach { codec ->
            listOf(byteArrayOf(1), byteArrayOf(1, 2, 3, 4)).forEach { payload ->
                val decoded = roundTrip(packet(codec, payload))
                assertEquals(codec, decoded.codecType)
                assertArrayEquals(payload, decoded.codecData)
            }
        }
    }

    private fun packet(
        codec: CodecType,
        payload: ByteArray,
    ): PacketBody0Voice =
        PacketBody0Voice(ProtocolRole.SERVER).apply {
            packetId = 65_535
            clientId = 7
            codecType = codec
            codecData = payload
        }

    private fun roundTrip(original: PacketBody0Voice): PacketBody0Voice {
        val bytes = ByteBuffer.allocate(original.size)
        original.write(bytes)
        bytes.flip()
        return PacketBody0Voice(ProtocolRole.SERVER).apply {
            read(ServerPacketHeader(), bytes)
        }
    }
}
