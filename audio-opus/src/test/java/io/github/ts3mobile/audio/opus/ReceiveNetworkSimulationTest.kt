package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReceiveNetworkSimulationTest {
    @Test fun tenMinuteTracesCoverLossBurstsDuplicatesReorderingAndClockDriftWithoutUnboundedMedia() {
        val cases =
            listOf(
                Scenario("healthy", clients = 1),
                Scenario("loss1", loss = 1), Scenario("loss3", loss = 3), Scenario("loss5", loss = 5),
                Scenario("burst3", burst = true), Scenario("reorder-duplicate", jitter = 80, duplicate = true),
                Scenario("eight-fast-clock", clients = 8, driftPpm = -100),
                Scenario("eight-slow-clock", clients = 8, driftPpm = 100),
                Scenario("pause100", pauseMs = 100), Scenario("pause500", pauseMs = 500), Scenario("pause5000", pauseMs = 5000),
            )
        for (scenario in cases) {
            val observed = run(scenario)
            assertTrue("${scenario.name}: ${observed.decoded}", observed.decoded.all { it > 20_000 })
            assertTrue(observed.maxInputAge <= 60 * MS)
            assertTrue(observed.maxWait < 500 * MS)
            if (scenario.pauseMs > 60) assertTrue(observed.stale > 0)
            println("A4B trace ${scenario.name}: $observed")
        }
    }

    @Test fun trainedAdaptiveTalkspurtsRecoverMoreDeadlineEligiblePacketsThanFixedSixty() {
        val fixed = talkspurts(false)
        val adaptive = talkspurts(true)
        println("A4B adaptive comparison: fixed late=$fixed, adaptive late=$adaptive")
        assertTrue("fixed=$fixed adaptive=$adaptive", adaptive < fixed)
    }

    @Test fun healthyTraceAddsNoMoreThanTwentyMillisecondsComparedWithFixedSixty() {
        val scenario = Scenario("healthy-comparison")
        val fixed = run(scenario, false)
        val adaptive = run(scenario, true)
        assertEquals(fixed.decoded, adaptive.decoded)
        assertTrue(adaptive.maxWait <= fixed.maxWait + 20 * MS)
        println("A4B healthy wait: fixed=${fixed.maxWait}, adaptive=${adaptive.maxWait}")
    }

    private fun talkspurts(adaptive: Boolean): Long {
        val random = Random(29)
        val budget = ReceiveMediaBudget()
        val seen = mutableSetOf<Int>()
        val decoder = Decoder(seen, IntArray(1))
        TalkerJitterPipeline(decoderFactory = { decoder }, policy = ReceivePolicy(adaptive), budget = budget).use { pipeline ->
            repeat(30) { burst ->
                val start = burst * 3_000 * MS
                val arrivals =
                    (0 until 100).map { index ->
                        val sequence = burst * 100 + index
                        queued(0, sequence, start + (index * 20 + random.nextInt(0, 111)) * MS)
                    }.sortedBy { it.arrivalNanos }
                var cursor = 0
                for (tick in 0..260) {
                    val now = start + tick * 10 * MS
                    while (cursor < arrivals.size && arrivals[cursor].arrivalNanos <= now) pipeline.offer(arrivals[cursor++])
                    pipeline.fillPcm(now)
                    pipeline.readPcm(480)
                }
                pipeline.finishTalkspurt()
            }
        }
        return budget.stats.latePackets.get()
    }

    private fun run(
        scenario: Scenario,
        adaptive: Boolean = true,
    ): Observed {
        val random = Random(28)
        val arrivals = ArrayList<QueuedFrame>()
        repeat(scenario.clients) { client ->
            repeat(30_000) { index ->
                if (random.nextInt(100) < scenario.loss || (scenario.burst && index % 100 in 30..32)) return@repeat
                val mediaNanos = index * 20 * MS + index * 20 * MS * scenario.driftPpm / 1_000_000
                val arrival = mediaNanos + random.nextInt(scenario.jitter + 1) * MS
                val queued = queued(client, (65_500 + index) and 0xffff, arrival)
                arrivals += queued
                if (scenario.duplicate && index % 13 == 0) arrivals += queued.copy(arrivalNanos = arrival + 2 * MS)
            }
        }
        arrivals.sortBy { it.arrivalNanos }
        val queue = VoiceReceiveQueue({ OpusPacketInfo(960) })
        val seen = mutableSetOf<Int>()
        val decoded = IntArray(scenario.clients)
        var cursor = 0
        PlaybackStreams(queue, mutableSetOf(), emptyMap(), ReceivePolicy(adaptive)) { Decoder(seen, decoded) }.use { streams ->
            for (tick in 0..60_100) {
                val now = tick * 10 * MS
                while (cursor < arrivals.size && arrivals[cursor].arrivalNanos <= now) queue.offer(arrivals[cursor++], now)
                if (now in 120_000 * MS until (120_000 + scenario.pauseMs) * MS) continue
                streams.drainAvailable(256, now)
                streams.prepare(now)
                streams.mix(now)
                repeat(scenario.clients) { assertTrue(queue.budget.samples(it) <= ReceivePolicy.MAX_CLIENT_SAMPLES) }
            }
        }
        val stats = queue.budget.stats
        return Observed(
            decoded.toList(),
            stats.stalePackets.get(),
            stats.plcPackets.get(),
            stats.maxInputAgeNanos.get(),
            stats.maxCombinedWaitNanos.get(),
        )
    }

    private fun queued(
        client: Int,
        sequence: Int,
        arrival: Long,
    ) = QueuedFrame(
        VoiceFrame(
            client,
            sequence,
            VoiceCodec.OPUS_VOICE,
            byteArrayOf(client.toByte(), (sequence shr 8).toByte(), sequence.toByte()),
            false,
        ),
        arrival,
    )

    private class Decoder(private val seen: MutableSet<Int>, private val counts: IntArray) : OpusPacketDecoder {
        override fun packetInfo(packet: ByteArray) = OpusPacketInfo(960)

        override fun decodeFec(
            packet: ByteArray,
            frameSize: Int,
        ): ShortArray = error("No FEC in network fixture")

        override fun decode(
            packet: ByteArray?,
            frameSize: Int,
        ): ShortArray {
            if (packet == null) return ShortArray(frameSize)
            val client = packet[0].toInt() and 0xff
            val sequence = ((packet[1].toInt() and 0xff) shl 8) or (packet[2].toInt() and 0xff)
            assertTrue("Repeated normal decode: client=$client packet=$sequence", seen.add((client shl 16) or sequence))
            counts[client]++
            return ShortArray(960) { 100 }
        }

        override fun reset() = Unit

        override fun close() = Unit
    }

    private data class Scenario(
        val name: String,
        val clients: Int = 1,
        val loss: Int = 0,
        val burst: Boolean = false,
        val jitter: Int = 15,
        val duplicate: Boolean = false,
        val driftPpm: Long = 0,
        val pauseMs: Long = 0,
    )

    private data class Observed(val decoded: List<Int>, val stale: Long, val plc: Long, val maxInputAge: Long, val maxWait: Long)

    private companion object {
        const val MS = 1_000_000L
    }
}
