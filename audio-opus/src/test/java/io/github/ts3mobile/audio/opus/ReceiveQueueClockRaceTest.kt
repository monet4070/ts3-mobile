package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveQueueClockRaceTest {
    @Test
    fun arrivalAfterWorkerTimeSnapshotIsNotAnExpiredPacket() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        assertTrue(queue.offer(queued(0, 0)))
        val previous = checkNotNull(queue.poll(0))
        assertTrue(queue.offer(queued(1, 20 * MS)))

        val arrivedDuringTick = queue.poll(20 * MS - 1)

        assertNotNull(arrivedDuringTick)
        assertTrue(checkNotNull(previous.lease).valid)
        assertEquals(0L, queue.budget.stats.stalePackets.get())
        assertEquals(1920, queue.budget.samples(1))
        assertEquals(0L, queue.budget.stats.maxInputAgeNanos.get())
    }

    @Test
    fun olderProducerSnapshotDoesNotPruneANewerQueuedArrival() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val earlier = checkNotNull(queue.prepare(queued(0, 10 * MS)))
        val newer = checkNotNull(queue.prepare(queued(1, 20 * MS)))
        assertTrue(queue.offer(newer, 20 * MS))

        assertTrue(queue.offer(earlier, 15 * MS))

        assertEquals(2, queue.size)
        assertEquals(1, checkNotNull(queue.poll(21 * MS)).frame.packetId)
        assertEquals(0, checkNotNull(queue.poll(21 * MS)).frame.packetId)
        assertEquals(0L, queue.budget.stats.stalePackets.get())
    }

    @Test
    fun expiringOldInputPreservesNewerArrivalsAndTheirOriginalTimestamp() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        queue.offer(queued(0, 0))
        queue.offer(queued(1, 50 * MS))
        queue.offer(queued(2, 80 * MS))

        val first = checkNotNull(queue.poll(70 * MS))
        val second = checkNotNull(queue.poll(70 * MS))

        assertEquals(1, first.frame.packetId)
        assertEquals(50 * MS, first.arrivalNanos)
        assertEquals(2, second.frame.packetId)
        assertEquals(80 * MS, second.arrivalNanos)
        assertEquals(1L, queue.budget.stats.stalePackets.get())
    }

    @Test
    fun networkArrivalsDuringRouteQueriesDoNotClearContinuousSpeech() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val output = FakePlaybackOutput().apply { recordSamples = false }
        val decoder = CountingDecoder()
        var now = 0L
        var nextPacket = 0
        val routeCheckedOutput =
            object : PlaybackOutput by output {
                override val routeReady: Boolean
                    get() {
                        if (nextPacket < PACKETS && nextPacket * 20 * MS <= now) {
                            val arrival = now + MS
                            assertTrue(queue.offer(queued(nextPacket++, arrival), arrival))
                        }
                        return true
                    }
            }
        PlaybackSession(
            queue,
            PlaybackStreams(queue, mutableSetOf(), emptyMap()) { decoder },
            { routeCheckedOutput },
            { true },
            {},
        ).use { session ->
            repeat(PACKETS * 2 + 100) { tick ->
                now = tick * 10 * MS
                if (now < 60 * MS && nextPacket * 20 * MS <= now) {
                    assertTrue(queue.offer(queued(nextPacket++, now), now))
                }
                output.playbackHead = minOf(output.acceptedFrames, output.playbackHead + 480)
                session.tick(now)
            }
        }

        assertEquals(0L, queue.budget.stats.stalePackets.get())
        assertEquals(0L, queue.budget.stats.budgetResets.get())
        assertEquals(PACKETS, decoder.decodedPackets)
        assertEquals(PACKETS * 960L, output.acceptedFrames)
    }

    private fun queued(
        sequence: Int,
        arrival: Long,
    ) = QueuedFrame(VoiceFrame(1, sequence, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), arrival)

    private class CountingDecoder : OpusPacketDecoder {
        var decodedPackets = 0

        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960)

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            check(packet != null) { "No packet loss in this fixture" }
            decodedPackets++
            return ShortArray(960) { 1000 }
        }

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("No packet loss in this fixture")

        override fun reset() = Unit

        override fun close() = Unit
    }

    private companion object {
        const val MS = 1_000_000L
        const val PACKETS = 1000
    }
}
