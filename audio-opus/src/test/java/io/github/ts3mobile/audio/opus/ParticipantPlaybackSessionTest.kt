package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticipantPlaybackSessionTest {
    @Test fun pendingPartialAndAcceptedMixedBlocksNeverDiscardUnmixedBOrRebuildItsDecoder() {
        for (accepted in listOf(0, 120, 480)) {
            Fixture(accepted).use { fixture ->
                fixture.startDual()
                val b = fixture.decoders[1]
                val before = fixture.queue.budget.samples(B)
                assertEquals(4320, before)
                fixture.muteA()
                fixture.session.invalidateParticipants(setOf(A), 61 * MS)
                assertEquals(before, fixture.queue.budget.samples(B))
                assertEquals(1, fixture.queue.size)
                assertTrue(fixture.decoders[0].closed)
                assertFalse(b.closed)
                assertTrue(fixture.outputs.first().closed)
                assertEquals(1, fixture.discards)
                fixture.session.tick(70 * MS)
                fixture.gains.clear()
                fixture.queue.removeClient(A)
                val beforeUnmute = fixture.queue.budget.samples(B)
                fixture.session.invalidateParticipants(setOf(A), 71 * MS)
                assertEquals(beforeUnmute, fixture.queue.budget.samples(B))
                for (millis in 80L..150L step 10) fixture.session.tick(millis * MS)
                val actual = fixture.outputs.drop(1).flatMap { it.accepted }
                val expected = (0..4).flatMap { Fixture.pcm(B, it).toList() }.drop(480)
                assertEquals("Every retained B sample, in order, exactly once", expected, actual)
                assertEquals(0, fixture.queue.budget.samples(B))
                assertEquals(2, fixture.decoders.size)
                assertEquals(0, b.resets)
                assertFalse(b.closed)
                assertEquals(3, fixture.outputs.size)
                assertEquals(if (accepted == 0) 2 else 3, fixture.referenceStarts.size)
                assertTrue(fixture.referenceStarts.all { it == 0L })
                assertEquals(0L, fixture.queue.budget.stats.budgetResets.get())
                assertEquals(0L, fixture.queue.budget.stats.stalePackets.get())
            }
        }
    }

    @Test fun queuedPostUnmuteMediaSurvivesALateParticipantCommand() {
        Fixture().use { fixture ->
            fixture.startDual()
            fixture.muteA()
            fixture.gains.clear()
            fixture.queue.removeClient(A)
            fixture.feed(A, 10, 61 * MS)
            fixture.session.invalidateParticipants(setOf(A), 62 * MS)
            assertEquals(960, fixture.queue.budget.samples(A))
            assertEquals(2, fixture.queue.size)
            for (millis in 70L..180L step 10) fixture.session.tick(millis * MS)
            assertTrue(fixture.decoders.any { 10 in it.decodedSequences })
            assertEquals(0, fixture.queue.budget.samples(A))
        }
    }

    @Test fun alreadyIngestedPostUnmuteGenerationIsNotClosedByALateCommand() {
        Fixture().use { fixture ->
            fixture.startDual()
            val a = fixture.decoders[0]
            fixture.muteA()
            fixture.gains.clear()
            fixture.queue.removeClient(A)
            fixture.feed(A, 10, 61 * MS)
            fixture.streams.drainAvailable(256, 62 * MS)
            assertEquals(1, a.resets)
            fixture.session.invalidateParticipants(setOf(A), 63 * MS)
            assertFalse(a.closed)
            assertEquals(960, fixture.queue.budget.samples(A))
            for (millis in 70L..180L step 10) fixture.session.tick(millis * MS)
            assertTrue(10 in a.decodedSequences)
            assertEquals(0, fixture.queue.budget.samples(A))
        }
    }

    @Test fun failedParticipantRetirementBlocksOverlappingOutputCreation() {
        Fixture().use { fixture ->
            fixture.startDual()
            fixture.outputs.single().failClose = true
            fixture.muteA()
            fixture.session.invalidateParticipants(setOf(A), 61 * MS)
            assertEquals(PlaybackStatus.FAILED, fixture.session.status)
            fixture.session.tick(70 * MS)
            assertEquals(1, fixture.outputs.size)
            assertFalse(fixture.outputs.single().closed)
            fixture.outputs.single().failClose = false
        }
    }

    @Test fun participantChangeDoesNotShortenGlobalFocusRetryOrPreserveRevokedB() {
        Fixture().use { fixture ->
            fixture.startDual()
            val b = fixture.decoders[1]
            fixture.session.invalidate(70 * MS, focusInterrupted = true)
            fixture.session.invalidateParticipants(setOf(A), 80 * MS)
            fixture.feed(B, 10, 120 * MS)
            fixture.session.tick(180 * MS)
            assertEquals(1, fixture.focusRequests)
            assertEquals(PlaybackStatus.RETRYING, fixture.session.status)
            assertTrue(b.closed)
            assertEquals(0, fixture.queue.budget.samples(B))
            fixture.feed(B, 11, 400 * MS)
            fixture.session.tick(460 * MS)
            assertEquals(2, fixture.focusRequests)
            assertEquals(PlaybackStatus.PLAYING, fixture.session.status)
        }
    }

    @Test fun outputFailureAfterParticipantRetirementRetainsBoundedGlobalRecovery() {
        Fixture().use { fixture ->
            fixture.startDual()
            fixture.muteA()
            fixture.session.invalidateParticipants(setOf(A), 61 * MS)
            fixture.nextWriteFails = true
            fixture.session.tick(70 * MS)
            assertEquals(PlaybackStatus.RETRYING, fixture.session.status)
            assertEquals(1L, fixture.queue.budget.stats.outputFailures.get())
            assertEquals(0, fixture.queue.budget.samples(B))
            assertTrue(fixture.outputs.all { it.closed })
            assertTrue(fixture.decoders.all { it.closed })
        }
    }

    private class Fixture(private val initialAccepted: Int = 0) : AutoCloseable {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val gains = mutableMapOf<Int, Float>()
        val decoders = mutableListOf<Decoder>()
        val streams = PlaybackStreams(queue, mutableSetOf(), gains) { Decoder().also(decoders::add) }
        val outputs = mutableListOf<FakePlaybackOutput>()
        val referenceStarts = mutableListOf<Long>()
        var discards = 0
        var focusRequests = 0
        var nextWriteFails = false
        private var firstReferenceWrite = true
        private val reference =
            object : PlaybackReferenceSink {
                override fun start() {
                    firstReferenceWrite = true
                }

                override fun accepted(
                    samples: ShortArray,
                    offset: Int,
                    count: Int,
                    firstFrame: Long,
                    acceptedAt: Long,
                    timing: RenderTiming,
                ) {
                    if (firstReferenceWrite) referenceStarts += firstFrame
                    firstReferenceWrite = false
                }

                override fun discard() {
                    discards++
                }
            }
        val session =
            PlaybackSession(
                queue,
                streams,
                {
                    assertTrue(outputs.all { it.closed })
                    FakePlaybackOutput().also {
                        it.playbackHead = 777L
                        if (outputs.isEmpty()) {
                            it.writeResults.add(initialAccepted)
                            if (initialAccepted < 480) it.writeResults.add(0)
                        }
                        if (nextWriteFails) it.writeResults.add(-6)
                        outputs += it
                    }
                },
                {
                    focusRequests++
                    true
                },
                {},
                reference,
            )

        fun startDual() {
            repeat(4) { sequence ->
                feed(A, sequence, 0)
                feed(B, sequence, 0)
            }
            session.tick(0)
            session.tick(60 * MS)
            feed(B, 4, 60 * MS)
        }

        fun muteA() {
            gains[A] = 0f
            queue.removeClient(A)
        }

        fun feed(
            client: Int,
            sequence: Int,
            now: Long,
        ) {
            assertTrue(
                queue.offer(
                    QueuedFrame(
                        VoiceFrame(client, sequence, VoiceCodec.OPUS_VOICE, byteArrayOf(client.toByte(), sequence.toByte()), false),
                        now,
                    ),
                ),
            )
        }

        override fun close() = session.close()

        companion object {
            fun pcm(
                client: Int,
                sequence: Int,
            ) = ShortArray(960) { ((if (client == B) 1000 else 200) + sequence * 20 + it % 20).toShort() }
        }
    }

    private class Decoder : OpusPacketDecoder {
        var closed = false
        var resets = 0
        val decodedSequences = mutableListOf<Int>()

        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960)

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            check(packet != null)
            val sequence = packet[1].toInt()
            decodedSequences += sequence
            return Fixture.pcm(packet[0].toInt(), sequence)
        }

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("Unexpected FEC")

        override fun reset() {
            resets++
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val A = 1
        const val B = 2
        const val MS = 1_000_000L
    }
}
