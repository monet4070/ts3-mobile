package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceCodec
import io.github.ts3mobile.protocol.VoiceFrame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class NativeOpusReceiveTest {
    @Test fun nativeMetadataMatchesEveryStoredDurationAndRejectsMalformedPackets() {
        OpusCompatibilityFixtures.load().forEach { fixture ->
            fixture.packets.forEach { packet ->
                assertEquals(fixture.samples, NativeOpusInfo.inspect(packet).samples)
            }
        }
        for (packet in listOf(byteArrayOf(), byteArrayOf(0xff.toByte()), byteArrayOf(3, 0), byteArrayOf(3, 63))) {
            assertThrows(IllegalArgumentException::class.java) { NativeOpusInfo.inspect(packet) }
        }
    }

    @Test fun realRedundancyOutperformsPlcForTheSameMissingFrame() {
        val cases = fecCases()
        val fec = cases.single { it.enabled }
        val control = cases.single { !it.enabled }
        assertTrue(fec.packets.count { NativeOpusInfo.inspect(it).hasFec } >= 10)
        assertTrue(control.packets.none { NativeOpusInfo.inspect(it).hasFec })
        var fecError = 0.0
        var plcError = 0.0
        var compared = 0
        for (lost in 30..140 step 10) {
            if (!NativeOpusInfo.inspect(fec.packets[lost + 1]).hasFec) continue
            NativeOpusDecoder().use { reference ->
                NativeOpusDecoder().use { recovered ->
                    NativeOpusDecoder().use { concealed ->
                        repeat(lost) { index ->
                            reference.decode(fec.packets[index], 5760)
                            recovered.decode(fec.packets[index], 5760)
                            concealed.decode(fec.packets[index], 5760)
                        }
                        val expected = reference.decode(fec.packets[lost], 5760)
                        val fromFec = recovered.decodeFec(fec.packets[lost + 1], 960)
                        val fromPlc = concealed.decode(null, 960)
                        assertEquals(960, fromFec.size)
                        assertEquals(960, recovered.decode(fec.packets[lost + 1], 5760).size)
                        expected.indices.forEach { index ->
                            val fecDifference = expected[index].toDouble() - fromFec[index]
                            val plcDifference = expected[index].toDouble() - fromPlc[index]
                            fecError += fecDifference * fecDifference
                            plcError += plcDifference * plcDifference
                        }
                        compared++
                    }
                }
            }
        }
        println("A4B confirmed FEC frames=$compared, error ratio FEC/PLC=${fecError / plcError}")
        assertTrue(compared >= 3)
        assertTrue(fecError < plcError * 0.9)
    }

    @Test fun fecWithoutRedundancyRemainsFiniteAndCorruptRecoveryFailsExplicitly() {
        val control = fecCases().single { !it.enabled }
        NativeOpusDecoder().use { decoder ->
            decoder.decode(control.packets[0], 5760)
            assertEquals(960, decoder.decodeFec(control.packets[2], 960).size)
            assertThrows(IllegalArgumentException::class.java) { decoder.decodeFec(byteArrayOf(0xff.toByte()), 960) }
        }
    }

    @Test fun pipelineWithConfirmedDurationRecoversOnceAndNormalDecodesTheFollowingPacketOnce() {
        val fixture = fecCases().single { it.enabled }
        val lost = (30..100).first { NativeOpusInfo.inspect(fixture.packets[it + 1]).hasFec }
        var normal = 0
        var fec = 0
        var plc = 0
        val budget = ReceiveMediaBudget()
        val factory = {
            val native = NativeOpusDecoder()
            object : OpusPacketDecoder {
                override fun packetInfo(packet: ByteArray) = native.packetInfo(packet)

                override fun decodeFec(
                    packet: ByteArray,
                    frameSize: Int,
                ): ShortArray {
                    fec++
                    return native.decodeFec(packet, frameSize)
                }

                override fun decode(
                    packet: ByteArray?,
                    frameSize: Int,
                ): ShortArray {
                    if (packet == null) plc++ else normal++
                    return native.decode(packet, frameSize)
                }

                override fun reset() = native.reset()

                override fun close() = native.close()
            }
        }
        TalkerJitterPipeline(decoderFactory = factory, policy = ReceivePolicy(confirmedPacketSamples = 960), budget = budget).use {
                pipeline ->
            var samples = 0
            fixture.packets.take(lost + 4).forEachIndexed { index, packet ->
                if (index != lost) pipeline.offer(QueuedFrame(VoiceFrame(1, index, VoiceCodec.OPUS_VOICE, packet, false), 0))
                pipeline.fillPcm(60_000_000L + index * 20_000_000L)
                samples += pipeline.readPcm(960).size
            }
            repeat(3) {
                pipeline.fillPcm(4_000_000_000)
                samples += pipeline.readPcm(960).size
            }
            assertEquals(lost + 3, normal)
            assertEquals(1, fec)
            assertEquals(0, plc)
            assertEquals((lost + 4) * 960, samples)
            assertEquals(1L, budget.stats.fecAttempts.get())
        }
    }

    @Test fun transportWithoutConfirmedFrameDurationDoesNotGuessFecEligibility() {
        val fixture = fecCases().single { it.enabled }
        assertTrue(fixture.packets.any { NativeOpusInfo.inspect(it).hasFec })
        val policy = ReceivePolicy()
        assertEquals(null, policy.confirmedPacketSamples)
        assertFalse(fecCases().single { !it.enabled }.packets.any { NativeOpusInfo.inspect(it).hasFec })
    }

    private fun fecCases(): List<FecCase> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("opus/fec-1.6.1.json").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val expected = JSONObject(assets.open("opus/fec-sha256.json").bufferedReader().use { it.readText() }).getString("fec-1.6.1.json")
        check(hash == expected)
        val corpus = JSONObject(bytes.toString(Charsets.UTF_8))
        check(corpus.getInt("samples") == 960 && corpus.getInt("sampleRate") == 48000)
        val cases = corpus.getJSONArray("cases")
        return (0 until cases.length()).map { index ->
            val scenario = cases.getJSONObject(index)
            val packets = scenario.getJSONArray("packets")
            val decoded =
                (0 until packets.length()).map { packet ->
                    val encoded = packets.getJSONObject(packet)
                    val hex = encoded.getString("hex")
                    val payload = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                    assertEquals(encoded.getBoolean("lbrr"), NativeOpusInfo.inspect(payload).hasFec)
                    payload
                }
            FecCase(scenario.getBoolean("fecEnabled"), decoded)
        }
    }

    private data class FecCase(val enabled: Boolean, val packets: List<ByteArray>)
}
