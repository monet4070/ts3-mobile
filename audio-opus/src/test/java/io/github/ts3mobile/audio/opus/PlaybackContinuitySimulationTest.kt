package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackContinuitySimulationTest {
    @Test
    fun tenMinuteSeededTraceDeliversEverySampleOnceAndDrainsWithoutTimeout() {
        val random = Random(24)
        val arrivals =
            (0 until PACKETS).map { packetId ->
                val arrival = (packetId * 20L + random.nextInt(0, 16)) * MILLIS
                QueuedFrame(VoiceFrame(1, packetId, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), arrival)
            }.sortedBy { it.arrivalNanos }
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val output = FakePlaybackOutput().apply { recordSamples = false }
        val timeline = PlaybackTimeline(output)
        val decoder = CountingDecoder()
        var nextArrival = 0
        PlaybackStreams(queue, mutableSetOf(), emptyMap()) { decoder }.use { streams ->
            for (tick in 0..60_100) {
                val now = tick * TICK
                while (nextArrival < arrivals.size && arrivals[nextArrival].arrivalNanos <= now) {
                    assertTrue(queue.offer(arrivals[nextArrival++]))
                }
                streams.drainAvailable(256, now)
                streams.prepare(now)
                if (timeline.state == PlaybackTimeline.State.IDLE && streams.readyToStart(now)) {
                    timeline.start()
                    output.start()
                }
                if (timeline.state != PlaybackTimeline.State.IDLE) {
                    output.playbackHead = minOf(timeline.submittedFrames, output.playbackHead + 480)
                    timeline.tick(streams.mix(now), now)
                    if (timeline.state == PlaybackTimeline.State.IDLE) streams.finishTalkspurts()
                }
            }
        }
        assertEquals(PACKETS, decoder.decodedPackets)
        assertEquals(0, decoder.concealedPackets)
        assertEquals(PACKETS * 960L, output.acceptedFrames)
        assertEquals(0L, timeline.drainTimeoutCount)
        assertEquals(1, output.startCount)
        assertEquals(1, output.discardCount)
        assertEquals(PlaybackTimeline.State.IDLE, timeline.state)
    }

    private class CountingDecoder : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960)

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("No FEC in this fixture")

        var decodedPackets = 0
        var concealedPackets = 0

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            if (packet == null) concealedPackets++ else decodedPackets++
            return ShortArray(if (packet == null) frameSize else 960) { 100 }
        }

        override fun reset() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val PACKETS = 30_000
        const val MILLIS = 1_000_000L
        const val TICK = 10 * MILLIS
    }
}
