package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSchedulingRegressionTest {
    @Test
    fun shortWorkerStallsDoNotDiscardHealthyContinuousSpeech() {
        assertLosslessTrace(packetSamples = 960, stallMs = 25)
    }

    @Test
    fun shortStallRecoveryAlsoPreservesTenMillisecondPackets() {
        assertLosslessTrace(packetSamples = 480, stallMs = 25)
    }

    @Test
    fun shortStallsThroughTheCatchUpBoundaryPreserveEverySample() {
        for (stallMs in listOf(0L, 5L, 10L, 40L, 50L)) {
            assertLosslessTrace(packetSamples = 960, stallMs = stallMs)
        }
    }

    private fun assertLosslessTrace(
        packetSamples: Int,
        stallMs: Long,
    ) {
        val packetMillis = packetSamples / 48L
        val queue = VoiceReceiveQueue({ OpusPacketInfo(packetSamples) })
        val outputs = mutableListOf<FakePlaybackOutput>()
        val decoder = CountingDecoder(packetSamples)
        val pacer = PlaybackPacer()
        var now = ORIGIN
        var previous = now
        var nextPacket = 0
        var ticks = 0
        PlaybackSession(
            queue,
            PlaybackStreams(queue, mutableSetOf(), emptyMap()) { decoder },
            { FakePlaybackOutput().apply { recordSamples = false }.also(outputs::add) },
            { true },
            {},
        ).use { session ->
            while (now < ORIGIN + (PACKETS * packetMillis + 1000) * MS) {
                now += pacer.waitNanos(now)
                if (ticks % 10 == 9 && nextPacket < PACKETS) now += stallMs * MS
                outputs.lastOrNull()?.takeUnless { it.closed }?.let { output ->
                    output.playbackHead = minOf(output.acceptedFrames, output.playbackHead + (now - previous) * 48_000 / SECOND)
                }
                while (nextPacket < PACKETS && ORIGIN + nextPacket * packetMillis * MS <= now) {
                    val arrival = ORIGIN + nextPacket * packetMillis * MS
                    queue.offer(QueuedFrame(VoiceFrame(1, nextPacket++, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), arrival), now)
                }
                session.tick(now)
                pacer.completed(now)
                previous = now
                ticks++
            }
        }
        val stats = queue.budget.stats
        println(
            "Scheduling trace: packetSamples=$packetSamples stallMs=$stallMs " +
                "packets=${decoder.decodedPackets}/$PACKETS accepted=${outputs.sumOf { it.acceptedFrames }} " +
                "budgetResets=${stats.budgetResets.get()} stale=${stats.stalePackets.get()} " +
                "maxWaitMs=${stats.maxCombinedWaitNanos.get() / MS}",
        )
        assertEquals("Healthy packets must not exhaust the receive budget", 0L, stats.budgetResets.get())
        assertEquals(0L, stats.stalePackets.get())
        assertEquals(PACKETS, decoder.decodedPackets)
        assertEquals(PACKETS * packetSamples.toLong(), outputs.sumOf { it.acceptedFrames })
        assertEquals(1, outputs.size)
    }

    private class CountingDecoder(private val packetSamples: Int) : OpusPacketDecoder {
        var decodedPackets = 0

        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(packetSamples)

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            check(packet != null) { "A lossless trace must not need concealment" }
            decodedPackets++
            return ShortArray(packetSamples) { 1000 }
        }

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("A lossless trace must not need FEC")

        override fun reset() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
        const val ORIGIN = 1_000_000_000_000L
        const val PACKETS = 1000
    }
}
