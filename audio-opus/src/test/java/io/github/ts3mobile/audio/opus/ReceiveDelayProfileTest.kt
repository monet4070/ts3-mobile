package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveDelayProfileTest {
    @Test
    fun cleanSpeechPreservesEverySampleAndStartsThirtyMillisecondsLater() {
        val balanced = play(ReceiveDelayProfile.BALANCED)
        val stability = play(ReceiveDelayProfile.STABILITY)

        assertEquals(expectedSpeech, balanced.samples)
        assertEquals(expectedSpeech, stability.samples)
        assertEquals(60L, balanced.firstWriteMs)
        assertEquals(90L, stability.firstWriteMs)
        assertEquals(0L, balanced.resets)
        assertEquals(0L, stability.resets)
        assertEquals(listOf(OutputRetirement.DRAINED.code), stability.retirements)
    }

    @Test
    fun additionalHeadroomRecoversASeventyFiveMillisecondLatePacket() {
        val balanced = play(ReceiveDelayProfile.BALANCED, delayMs = 75)
        val stability = play(ReceiveDelayProfile.STABILITY, delayMs = 75)

        assertEquals(1L, balanced.concealed)
        assertEquals(1L, balanced.late)
        assertEquals(0L, stability.concealed)
        assertEquals(0L, stability.late)
        assertEquals(0L, stability.resets)
        assertEquals(expectedSpeech, stability.samples)
    }

    @Test
    fun additionalHeadroomDoesNotRestoreAPermanentlyMissingPacket() {
        val balanced = play(ReceiveDelayProfile.BALANCED, losePacket = true)
        val stability = play(ReceiveDelayProfile.STABILITY, losePacket = true)

        assertEquals(1L, balanced.concealed)
        assertEquals(1L, stability.concealed)
        assertEquals(balanced.samples, stability.samples)
    }

    private fun play(
        profile: ReceiveDelayProfile,
        delayMs: Long = 0,
        losePacket: Boolean = false,
    ): Trial {
        val queue = VoiceReceiveQueue({ OpusPacketInfo(PACKET_SAMPLES) })
        val stats = queue.budget.stats
        val retirements = mutableListOf<Long>()
        stats.observer =
            object : AudioDiagnosticsObserver {
                override fun onEvent(
                    kind: AudioDiagnosticEventKind,
                    value: Long,
                ) {
                    if (kind == AudioDiagnosticEventKind.OUTPUT_RETIRED) retirements += value
                }

                override fun onStopped(snapshot: AudioReceiveDiagnostics) = Unit
            }
        val arrivals =
            (0 until PACKETS).filterNot { losePacket && it == DELAYED_PACKET }.map { sequence ->
                val arrivalMs = sequence * 20L + if (sequence == DELAYED_PACKET) delayMs else 0L
                val packet = VoiceFrame(1, sequence, VoiceCodec.OPUS_VOICE, byteArrayOf((sequence + 1).toByte()), false)
                QueuedFrame(packet, arrivalMs * MILLIS)
            }.sortedBy { it.arrivalNanos }
        val outputs = mutableListOf<FakePlaybackOutput>()
        var next = 0
        var firstWriteMs: Long? = null
        val streams = PlaybackStreams(queue, mutableSetOf(), emptyMap(), ReceivePolicy(delayProfile = profile)) { Decoder() }
        PlaybackSession(queue, streams, { FakePlaybackOutput().also(outputs::add) }).use { session ->
            for (millis in 0L..1_000L step 5L) {
                val now = millis * MILLIS
                while (next < arrivals.size && arrivals[next].arrivalNanos <= now) {
                    assertTrue(queue.offer(arrivals[next++]))
                }
                if (millis % 10L != 0L) continue
                outputs.lastOrNull()?.takeUnless { it.closed }?.let { it.playbackHead = it.acceptedFrames }
                session.tick(now)
                if (firstWriteMs == null && outputs.any { it.acceptedFrames > 0 }) firstWriteMs = millis
            }
        }
        assertEquals(1, outputs.size)
        assertEquals(0L, stats.stalePackets.get())
        return Trial(
            checkNotNull(firstWriteMs),
            outputs.single().accepted,
            stats.plcPackets.get(),
            stats.latePackets.get(),
            stats.budgetResets.get(),
            retirements,
        )
    }

    private class Decoder : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(PACKET_SAMPLES)

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray = if (packet == null) ShortArray(frameSize) { -1 } else ShortArray(PACKET_SAMPLES) { packet.single().toShort() }

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("No FEC in this fixture")

        override fun reset() = Unit

        override fun close() = Unit
    }

    private data class Trial(
        val firstWriteMs: Long,
        val samples: List<Short>,
        val concealed: Long,
        val late: Long,
        val resets: Long,
        val retirements: List<Long>,
    )

    private companion object {
        const val PACKETS = 20
        const val DELAYED_PACKET = 8
        const val PACKET_SAMPLES = 960
        const val MILLIS = 1_000_000L
        val expectedSpeech = List(PACKETS * PACKET_SAMPLES) { index -> (index / PACKET_SAMPLES + 1).toShort() }
    }
}
