package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStreamsTest {
    @Test
    fun oneShortTalkerFinishingDoesNotInterruptAnotherTalker() {
        val queue = queue()
        val decoders = mutableListOf<Decoder>()
        val streams = PlaybackStreams(queue, mutableSetOf(), emptyMap()) { Decoder().also(decoders::add) }
        streams.use {
            streams.ingest(queued(1, 0, 120))
            streams.ingest(queued(2, 0, 960))
            assertFalse(streams.readyToStart(0L))
            assertTrue(streams.readyToStart(READY_AT))
            val first = streams.mix(READY_AT)!!
            assertEquals(480, first.size)
            assertArrayEquals(ShortArray(120) { 2_000 }, first.copyOfRange(0, 120))
            assertArrayEquals(ShortArray(360) { 1_000 }, first.copyOfRange(120, 480))
            assertArrayEquals(ShortArray(480) { 1_000 }, streams.mix(READY_AT + TICK))
            assertNull(streams.mix(READY_AT + 2 * TICK))
        }
        assertTrue(decoders.all { it.closed })
    }

    @Test
    fun sharedMediaBudgetRejectsOldBacklogWithoutRenewingCapacityOnTransfer() {
        val queue = queue()
        repeat(256) { queue.offer(queued(1, it, 960)) }
        assertTrue(queue.size <= 12)
        assertTrue(queue.budget.samples(1) <= ReceivePolicy.MAX_CLIENT_SAMPLES)
        PlaybackStreams(queue, mutableSetOf(), emptyMap()) { Decoder() }.use { streams ->
            val count = queue.size
            val samples = queue.budget.samples(1)
            assertEquals(count, streams.drainAvailable(256, 0))
            assertEquals(samples, queue.budget.samples(1))
            assertEquals(0, queue.size)
        }
    }

    @Test
    fun discontinuityRetiresOnlyTheAffectedDecoder() {
        val queue = queue()
        val discontinuities = mutableSetOf<Int>()
        val decoders = mutableListOf<Decoder>()
        PlaybackStreams(queue, discontinuities, emptyMap()) { Decoder().also(decoders::add) }.use { streams ->
            streams.ingest(queued(1, 0, 480))
            streams.ingest(queued(2, 0, 960))
            discontinuities += 1
            streams.prepare(READY_AT)
            assertTrue(decoders[0].closed)
            assertFalse(decoders[1].closed)
            assertArrayEquals(ShortArray(480) { 1_000 }, streams.mix(READY_AT))
        }
    }

    @Test fun gainsAreSmoothedByTheProductionStreamAndCloseDropsDspState() {
        val gains = mutableMapOf<Int, Float>()
        val streams = PlaybackStreams(queue(), mutableSetOf(), gains) { Decoder() }
        streams.use {
            streams.ingest(queued(1, 0, 2880))
            assertArrayEquals(ShortArray(480) { 1000 }, streams.mix(READY_AT))
            gains[1] = 2f
            val ramp = streams.mix(READY_AT + TICK)!! + streams.mix(READY_AT + 2 * TICK)!!
            assertEquals(1001, ramp.first().toInt())
            assertEquals(2000, ramp.last().toInt())
            gains[1] = 0f
            assertNull(streams.mix(READY_AT + 3 * TICK))
            streams.reset()
            gains.clear()
            streams.ingest(queued(1, 0, 480))
            assertArrayEquals(ShortArray(480) { 1000 }, streams.mix(READY_AT))
        }
    }

    @Test fun decoderOwnershipIsBoundedWhenManyClientIdsArrive() {
        val queue = queue()
        val decoders = mutableListOf<Decoder>()
        PlaybackStreams(queue, mutableSetOf(), emptyMap()) { Decoder().also(decoders::add) }.use { streams ->
            repeat(100) { client ->
                queue.offer(queued(client, 0, 960))
                streams.drainAvailable(256, 0)
            }
            assertTrue(decoders.count { !it.closed } <= ReceivePolicy.MAX_TRACKED_CLIENTS)
        }
        assertTrue(decoders.all { it.closed })
    }

    private fun queued(
        clientId: Int,
        packetId: Int,
        sampleCount: Int,
    ): QueuedFrame =
        QueuedFrame(
            VoiceFrame(
                clientId,
                packetId,
                VoiceCodec.OPUS_VOICE,
                byteArrayOf((sampleCount shr 8).toByte(), sampleCount.toByte()),
                false,
            ),
            0L,
        )

    private class Decoder : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff))

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("No FEC in this fixture")

        var closed = false

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            val count = if (packet == null) frameSize else ((packet[0].toInt() and 0xff) shl 8) or (packet[1].toInt() and 0xff)
            return ShortArray(count) { 1_000 }
        }

        override fun reset() = Unit

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val TICK = 10_000_000L
        const val READY_AT = 60_000_000L
    }

    private fun queue() = VoiceReceiveQueue({ Decoder().packetInfo(it) })
}
