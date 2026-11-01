package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkerRecoveryTest {
    @Test fun lateTrainingSurvivesLargeGapAndBudgetGenerationReset() {
        val budget = ReceiveMediaBudget()
        val decoder = Decoder(false)
        TalkerJitterPipeline(decoderFactory = { decoder }, budget = budget).use { pipeline ->
            pipeline.offer(queued(1))
            pipeline.fillPcm(60_000_000)
            pipeline.readPcm(960)
            repeat(12) { pipeline.offer(queued(0)) }
            assertEquals(120, pipeline.nextTargetMs)
            pipeline.offer(queued(10))
            pipeline.fillPcm(80_000_000)
            assertEquals(120, pipeline.nextTargetMs)
            budget.invalidate(1)
            pipeline.offer(queued(11))
            assertEquals(120, pipeline.activeTargetMs)
        }
    }

    @Test fun failedFecResetsMutatedDecoderThenContinuesWithRealPacketWithoutPlc() {
        val decoder = Decoder(true, failFec = true)
        val budget = ReceiveMediaBudget()
        TalkerJitterPipeline(decoderFactory = { decoder }, policy = ReceivePolicy(confirmedPacketSamples = 960), budget = budget).use {
            it.offer(queued(0))
            it.offer(queued(2))
            it.fillPcm(60_000_000)
            it.readPcm(960)
            it.fillPcm(80_000_000)
            val recovered = it.readPcm(10000)
            assertEquals(960, recovered.size)
            assertTrue(recovered.first() < recovered[239])
            assertEquals(listOf("normal:0", "fec:2", "normal:2"), decoder.calls)
            assertEquals(1, decoder.resets)
            assertEquals(1L, budget.stats.fecFailures.get())
            assertEquals(0, budget.samples(1))
        }
    }

    @Test fun confirmedSingleLossUsesFecThenDecodesTheNextPacketExactlyOnce() {
        val decoder = Decoder(true)
        val budget = ReceiveMediaBudget()
        TalkerJitterPipeline(decoderFactory = { decoder }, policy = ReceivePolicy(confirmedPacketSamples = 960), budget = budget).use {
            it.offer(queued(0))
            it.offer(queued(2))
            it.fillPcm(60_000_000)
            it.readPcm(960)
            it.fillPcm(80_000_000)
            assertEquals(1920, it.readPcm(10000).size)
            assertEquals(listOf("normal:0", "fec:2", "normal:2"), decoder.calls)
            assertEquals(1L, budget.stats.fecAttempts.get())
        }
    }

    @Test fun unknownDurationOrAbsentRedundancyUsesPlcWithoutClaimingFecSuccess() {
        for (confirmed in listOf<Int?>(null, 960)) {
            val decoder = Decoder(confirmed == null)
            TalkerJitterPipeline(decoderFactory = { decoder }, policy = ReceivePolicy(confirmedPacketSamples = confirmed)).use {
                it.offer(queued(0))
                it.offer(queued(2))
                it.fillPcm(60_000_000)
                it.readPcm(960)
                it.fillPcm(80_000_000)
                assertEquals(listOf("normal:0", "plc", "normal:2"), decoder.calls)
            }
        }
    }

    @Test fun twoLostPacketsNeverUseTheSameRedundancyToRecoverBoth() {
        val decoder = Decoder(true)
        TalkerJitterPipeline(decoderFactory = { decoder }, policy = ReceivePolicy(confirmedPacketSamples = 960)).use {
            it.offer(queued(0))
            it.offer(queued(3))
            repeat(4) { tick ->
                it.fillPcm(60_000_000L + tick * 20_000_000L)
                it.readPcm(960)
            }
            assertEquals(1, decoder.calls.count { call -> call == "fec:3" })
            assertEquals(1, decoder.calls.count { call -> call == "plc" })
            assertEquals(1, decoder.calls.count { call -> call == "normal:3" })
        }
    }

    @Test fun budgetResetDiscardsOldPcmAndFadesOnlyValidNewSamples() {
        val decoder = Decoder(false)
        val budget = ReceiveMediaBudget()
        TalkerJitterPipeline(decoderFactory = { decoder }, budget = budget).use { pipeline ->
            pipeline.offer(queued(0))
            pipeline.fillPcm(60_000_000)
            budget.invalidate(1)
            pipeline.offer(queued(1))
            pipeline.fillPcm(60_000_000)
            val pcm = pipeline.readPcm(10000)
            assertEquals(960, pcm.size)
            assertTrue(pcm.first() < pcm[239])
            assertEquals(1000, pcm[239].toInt())
            assertEquals(960L, pipeline.resetDroppedPcmSamples)
        }
    }

    private fun queued(sequence: Int) =
        QueuedFrame(
            VoiceFrame(1, sequence, VoiceCodec.OPUS_VOICE, byteArrayOf(sequence.toByte()), false),
            0,
        )

    private class Decoder(private val fec: Boolean, private val failFec: Boolean = false) : OpusPacketDecoder {
        val calls = mutableListOf<String>()
        var resets = 0
        private var dirty = false

        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960, hasFec = fec)

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray {
            calls += "fec:${packet[0]}"
            if (failFec) {
                dirty = true
                throw IllegalArgumentException("Synthetic mutated FEC state")
            }
            return ShortArray(frameSize) { 500 }
        }

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            check(!dirty) { "Decoder must be reset before reuse" }
            calls += if (packet == null) "plc" else "normal:${packet[0]}"
            return ShortArray(if (packet == null) frameSize else 960) { 1000 }
        }

        override fun reset() {
            dirty = false
            resets++
        }

        override fun close() = Unit
    }
}
