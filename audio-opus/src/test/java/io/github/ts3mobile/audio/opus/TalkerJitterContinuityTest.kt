package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkerJitterContinuityTest {
    @Test
    fun longDecodeIsPreservedAlongsideEarlierPcmWithinTheSharedBudget() {
        val decoder = SizedDecoder(mapOf(0 to 960, 1 to 5_760))
        TalkerJitterPipeline(decoderFactory = { decoder }).use { pipeline ->
            pipeline.offer(queued(0))
            pipeline.offer(queued(1))
            pipeline.fillPcm(READY_AT)
            assertEquals(0L, pipeline.resetDroppedPcmSamples)
            assertArrayEquals(ShortArray(960) { 1 } + ShortArray(5_760) { 2 }, pipeline.readPcm(10_000))
            assertTrue(pipeline.isFinishedTalkspurt())
        }
    }

    @Test
    fun longPacketLossCannotExpandConcealmentPastSixtyMilliseconds() {
        val decoder = SizedDecoder(mapOf(0 to 5_760, 3 to 5_760))
        TalkerJitterPipeline(decoderFactory = { decoder }).use { pipeline ->
            pipeline.offer(queued(0))
            pipeline.offer(queued(3))
            repeat(6) {
                pipeline.fillPcm(READY_AT + it * TICK)
                pipeline.readPcm(10_000)
            }
            assertEquals(listOf(0, 3), decoder.decodedIds)
            assertEquals(listOf(2_880), decoder.concealedSizes)
        }
    }

    @Test
    fun ordinaryLossIsLimitedToThreeTwentyMillisecondPackets() {
        val decoder = SizedDecoder(emptyMap())
        TalkerJitterPipeline(decoderFactory = { decoder }).use { pipeline ->
            pipeline.offer(queued(0))
            pipeline.offer(queued(4))
            repeat(8) {
                pipeline.fillPcm(READY_AT + it * TICK)
                pipeline.readPcm(10_000)
            }
            assertEquals(listOf(960, 960, 960), decoder.concealedSizes)
            assertEquals(listOf(0, 4), decoder.decodedIds)
        }
    }

    @Test
    fun emptyTransportPayloadDoesNotTriggerNativePlcOrCreateAnAudioStream() {
        val decoder = SizedDecoder(emptyMap())
        TalkerJitterPipeline(decoderFactory = { decoder }).use { pipeline ->
            pipeline.offer(queued(0).copy(frame = queued(0).frame.copy(encodedData = ByteArray(0))))
            pipeline.fillPcm(READY_AT)
            assertFalse(pipeline.readyToStart(READY_AT))
            assertEquals(0, pipeline.readPcm(480).size)
            assertTrue(decoder.decodedIds.isEmpty())
            assertTrue(decoder.concealedSizes.isEmpty())
        }
    }

    @Test
    fun temporaryEmptyPendingDoesNotResetTheDecoderOrAddUnboundedPlc() {
        val decoder = SizedDecoder(emptyMap())
        TalkerJitterPipeline(decoderFactory = { decoder }).use { pipeline ->
            pipeline.offer(queued(0))
            pipeline.fillPcm(READY_AT)
            pipeline.readPcm(10_000)
            repeat(100) { pipeline.fillPcm(READY_AT + it * TICK) }
            assertTrue(decoder.concealedSizes.isEmpty())
            assertEquals(0, decoder.resets)
            pipeline.offer(queued(1).copy(arrivalNanos = READY_AT + 40 * MILLIS))
            pipeline.fillPcm(READY_AT + 40 * MILLIS)
            assertEquals(listOf(0, 1), decoder.decodedIds)
            assertEquals(0, decoder.resets)
        }
    }

    @Test
    fun duplicateAndWraparoundPacketsDoNotRepeatPcm() {
        val decoder = SizedDecoder(emptyMap())
        TalkerJitterPipeline(decoderFactory = { decoder }).use { pipeline ->
            pipeline.offer(queued(65_535))
            pipeline.offer(queued(0))
            pipeline.offer(queued(0))
            pipeline.fillPcm(READY_AT)
            assertEquals(listOf(255, 0), decoder.decodedIds)
            assertEquals(1_920, pipeline.readPcm(10_000).size)
        }
    }

    private fun queued(packetId: Int): QueuedFrame =
        QueuedFrame(VoiceFrame(1, packetId, VoiceCodec.OPUS_VOICE, byteArrayOf(packetId.toByte()), false), 0L)

    private class SizedDecoder(private val sizes: Map<Int, Int>) : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(sizes[packet[0].toInt() and 0xff] ?: 960)

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("No FEC in this fixture")

        val decodedIds = mutableListOf<Int>()
        val concealedSizes = mutableListOf<Int>()
        var resets = 0

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            if (packet == null) {
                concealedSizes += frameSize
                return ShortArray(frameSize)
            }
            val packetId = packet[0].toInt() and 0xff
            decodedIds += packetId
            return ShortArray(sizes[packetId] ?: 960) { (packetId + 1).toShort() }
        }

        override fun reset() {
            resets++
        }

        override fun close() = Unit
    }

    private companion object {
        const val MILLIS = 1_000_000L
        const val TICK = 10 * MILLIS
        const val READY_AT = 60 * MILLIS
    }
}
