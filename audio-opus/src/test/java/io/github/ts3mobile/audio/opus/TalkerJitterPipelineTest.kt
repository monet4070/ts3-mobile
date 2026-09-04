package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Covers the pure buffering/reordering logic of the jitter pipeline with a
 * deterministic fake decoder; native Opus decode itself is covered by the
 * device test suite.
 */
class TalkerJitterPipelineTest {
    @Test
    fun reordersOutOfOrderPacketsBeforeDecoding() {
        val decoder = FakeDecoder()
        val pipeline = TalkerJitterPipeline(decoderFactory = { decoder })
        val now = System.nanoTime()
        pipeline.offer(queued(2, now))
        pipeline.offer(queued(0, now))
        pipeline.offer(queued(1, now))
        advance(pipeline, now, INITIAL_HOLD_NANOS + 10)

        // fillPcm consumes packets strictly in sequence order.
        assertTrue(pipeline.readPcm(TALKER_SAMPLES).isNotEmpty())
        assertEquals(listOf(0L, 1L, 2L), decoder.decodedPacketIds)
    }

    @Test
    fun ignoresDuplicateAndStalePackets() {
        val decoder = FakeDecoder()
        val pipeline = TalkerJitterPipeline(decoderFactory = { decoder })
        val now = System.nanoTime()

        // Start consuming at sequence 1, then deliver a stale packet 0 and a
        // duplicate packet 1; both must be ignored.
        pipeline.offer(queued(1, now))
        advance(pipeline, now, INITIAL_HOLD_NANOS + 1)
        assertTrue(pipeline.readPcm(TALKER_SAMPLES).isNotEmpty())

        pipeline.offer(queued(0, now + 1))
        pipeline.offer(queued(1, now + 2))
        advance(pipeline, now, INITIAL_HOLD_NANOS + 1)

        assertEquals(listOf(1L), decoder.decodedPacketIds)
    }

    @Test
    fun notReadyBeforeInitialHoldAndEmptyAfterTalkspurt() {
        val decoder = FakeDecoder()
        val pipeline = TalkerJitterPipeline(decoderFactory = { decoder })
        val now = System.nanoTime()

        pipeline.offer(queued(0, now))
        assertFalse(pipeline.readyToStart(now + 5))
        assertTrue(pipeline.readyToStart(now + INITIAL_HOLD_NANOS + 1))

        advance(pipeline, now, INITIAL_HOLD_NANOS + 10)
        while (pipeline.readPcm(TALKER_SAMPLES).isNotEmpty()) Unit
        assertTrue(pipeline.isFinishedTalkspurt())

        pipeline.finishTalkspurt()
        assertFalse(pipeline.isFinishedTalkspurt())
    }

    @Test
    fun concealsMissingPacketThenResumes() {
        val decoder = FakeDecoder()
        val pipeline = TalkerJitterPipeline(decoderFactory = { decoder })
        val now = System.nanoTime()
        pipeline.offer(queued(0, now))
        pipeline.offer(queued(2, now))
        val readyAt = now + INITIAL_HOLD_NANOS + 10

        // First fill decodes packet 0; concealment only runs once the PCM
        // buffer is below one mix tick, so drain before filling again.
        pipeline.fillPcm(readyAt)
        while (pipeline.readPcm(TALKER_SAMPLES).isNotEmpty()) Unit

        pipeline.fillPcm(readyAt)
        var totalSamples = 0
        while (true) {
            val chunk = pipeline.readPcm(TALKER_SAMPLES)
            if (chunk.isEmpty()) break
            totalSamples += chunk.size
        }

        // Packet 1 is missing: the pipeline conceals it with a null decode
        // and then resumes with packet 2.
        assertTrue(totalSamples > 0)
        assertTrue(decoder.decodedPacketIds.containsAll(listOf(0L, 2L)))
        assertTrue(decoder.nullDecodeCount >= 1)
    }

    private fun advance(
        pipeline: TalkerJitterPipeline,
        startNanos: Long,
        elapsedMs: Long,
    ) {
        pipeline.fillPcm(startNanos + TimeUnit.MILLISECONDS.toNanos(elapsedMs))
    }

    private fun queued(
        packetId: Int,
        arrivalNanos: Long,
    ): QueuedFrame =
        QueuedFrame(
            frame =
                VoiceFrame(
                    clientId = 1,
                    packetId = packetId,
                    codec = VoiceCodec.OPUS_VOICE,
                    encodedData = byteArrayOf(packetId.toByte()),
                    isWhisper = false,
                ),
            arrivalNanos = arrivalNanos,
        )

    private class FakeDecoder : OpusPacketDecoder {
        val decodedPacketIds = mutableListOf<Long>()
        var nullDecodeCount = 0

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            if (packet == null) {
                nullDecodeCount++
                return ShortArray(frameSize)
            }
            // Recover the packet sequence id encoded into the payload; emit
            // a realistic 20 ms voice-frame size (960 samples) like Opus.
            val sequence = packet[0].toInt() and 0xFF
            decodedPacketIds += sequence.toLong()
            return ShortArray(TalkerJitterPipeline.DEFAULT_PACKET_SAMPLES) { index ->
                (sequence * 100 + index).toShort()
            }
        }

        override fun reset() = Unit

        override fun close() = Unit
    }

    private companion object {
        val INITIAL_HOLD_NANOS = TalkerJitterPipeline.INITIAL_HOLD_NANOS
        const val TALKER_SAMPLES = 480
    }
}
