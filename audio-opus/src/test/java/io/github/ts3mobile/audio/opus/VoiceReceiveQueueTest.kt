package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceReceiveQueueTest {
    @Test fun preparedMetadataIsNotParsedAgainAndPayloadCannotBeChangedByCaller() {
        var inspections = 0
        val queue =
            VoiceReceiveQueue({
                inspections++
                OpusPacketInfo(960)
            })
        val original = queued(1, 0, 0)
        val prepared = queue.prepare(original)!!
        original.frame.encodedData.fill(0)
        assertTrue(queue.offer(prepared))
        assertEquals(1, inspections)
        assertTrue(queue.poll(0)!!.frame.encodedData.any { it.toInt() != 0 })
    }

    @Test fun stalledWorkerDropsAllExpiredInputBeforeAnyDecoderCanObserveIt() {
        for (clients in listOf(1, 8)) for (samples in listOf(120, 5760)) for (pauseMs in listOf(100L, 500L, 5000L)) {
            val queue = VoiceReceiveQueue({ OpusPacketInfo(samples) })
            repeat(clients) { client ->
                repeat(2) { packet -> assertTrue(queue.offer(queued(client, packet, 0))) }
            }
            val resumed = pauseMs * MS
            assertNull(queue.poll(resumed))
            assertEquals(0, queue.size)
            repeat(clients) { client -> assertTrue(queue.offer(queued(client, 2, resumed))) }
            val delivered = generateSequence { queue.poll(resumed) }.toList()
            assertEquals(clients, delivered.size)
            assertEquals((0 until clients).toSet(), delivered.map { it.frame.clientId }.toSet())
            assertTrue(delivered.all { it.arrivalNanos == resumed && it.frame.packetId == 2 })
            assertEquals((clients * 2).toLong(), queue.budget.stats.stalePackets.get())
        }
    }

    @Test fun exactAgeBoundarySurvivesButOneNanosecondOlderInputDoesNot() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        queue.offer(queued(1, 0, 0))
        assertTrue(queue.poll(60 * MS) != null)
        queue.offer(queued(1, 1, 0))
        assertNull(queue.poll(60 * MS + 1))
        assertFalse(queue.offer(queued(1, 2, 0), 60 * MS + 1))
    }

    @Test fun transfersRetainSharedBudgetAndOverloadInvalidatesOnlyTheAffectedTalker() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(5760) })
        queue.offer(queued(1, 0, 0))
        queue.offer(queued(2, 0, 0))
        val first = queue.poll(0)!!
        val other = queue.poll(0)!!
        assertEquals(5760, queue.budget.samples(1))
        queue.offer(queued(1, 1, 0))
        queue.offer(queued(1, 2, 0))
        assertFalse(first.lease!!.valid)
        assertTrue(other.lease!!.valid)
        assertEquals(5760, queue.budget.samples(1))
        assertEquals(5760, queue.budget.samples(2))
        assertEquals(2, queue.poll(0)!!.frame.packetId)
    }

    @Test fun malformedOversizeAndDuplicatePacketsCannotBypassAnyLimit() {
        val queue =
            VoiceReceiveQueue({ bytes ->
                require(bytes[0].toInt() != 0)
                OpusPacketInfo(120)
            })
        assertFalse(queue.offer(queued(1, 0, 0).copy(frame = queued(1, 0, 0).frame.copy(encodedData = byteArrayOf(0)))))
        assertFalse(queue.offer(queued(1, 1, 0).copy(frame = queued(1, 1, 0).frame.copy(encodedData = ByteArray(61441)))))
        assertTrue(queue.offer(queued(1, 2, 0)))
        repeat(1000) { assertFalse(queue.offer(queued(1, 2, 0))) }
        assertEquals(120, queue.budget.samples(1))
        assertEquals(1, queue.size)
        assertEquals(2L, queue.budget.stats.invalidPackets.get())
        assertEquals(1000L, queue.budget.stats.duplicates.get())
    }

    @Test fun packetAndByteGuardsRemainBoundedUnderManySmallAndLargeInputs() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(120) })
        repeat(1000) { index -> queue.offer(queued(index % 8, index / 8, 0)) }
        assertTrue(queue.size <= 256)
        assertTrue((0..7).all { queue.budget.samples(it) <= 11520 })
        val large = VoiceReceiveQueue({ OpusPacketInfo(5760) })
        repeat(100) { client ->
            val frame = queued(client, 0, 0)
            large.offer(frame.copy(frame = frame.frame.copy(encodedData = ByteArray(61440) { 1 })))
        }
        assertTrue(large.size <= 8)
    }

    @Test fun copyingPayloadPreventsCallerMutationFromChangingAlreadyInspectedPackets() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val packet = queued(1, 0, 0)
        queue.offer(packet)
        packet.frame.encodedData[0] = 99
        assertEquals(1, queue.poll(0)!!.frame.encodedData.single().toInt())
    }

    @Test fun expirationKeepsStillFreshPacketsOfTheSameClientAndTheirOriginalArrival() {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        queue.offer(queued(1, 0, 0))
        queue.offer(queued(1, 1, 50 * MS))
        val fresh = queue.poll(70 * MS)!!
        assertEquals(1, fresh.frame.packetId)
        assertEquals(50 * MS, fresh.arrivalNanos)
        assertEquals(960, queue.budget.samples(1))
    }

    private fun queued(
        client: Int,
        sequence: Int,
        arrival: Long,
    ) = QueuedFrame(VoiceFrame(client, sequence, VoiceCodec.OPUS_VOICE, byteArrayOf(1), false), arrival)

    private companion object {
        const val MS = 1_000_000L
    }
}
