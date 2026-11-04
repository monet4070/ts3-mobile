package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.VoiceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class NativeOpusCompatibilityTest {
    @Test fun packagedLibraryReportsTheExpectedVersion() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedOpusVersion") ?: "1.6.1"
        assertTrue("Packaged codec=${NativeOpusInfo.version}", NativeOpusInfo.version.contains(expected))
        println("A4A packaged codec=${NativeOpusInfo.version}")
    }

    @Test fun bothVersionCorporaCoverRealStereoDtxAndAllPacketDurations() {
        val fixtures = OpusCompatibilityFixtures.load()
        assertEquals(setOf("libopus 1.3.1", "libopus 1.6.1"), fixtures.map { it.producer }.toSet())
        assertEquals(setOf(120, 240, 480, 960, 1920, 2880, 5760), fixtures.map { it.samples }.toSet())
        assertTrue(fixtures.any { it.channels == 2 && it.leftHz > 0 && it.rightHz > 0 })
        fixtures.filter { it.dtxPackets > 0 }.forEach { fixture ->
            assertTrue(fixture.packets.all { it.isNotEmpty() })
            assertTrue(fixture.packets.any { it.size <= 2 })
        }
        assertEquals(2, fixtures.count { it.dtxPackets > 0 })
    }

    @Test fun decodesBothEncodersWithExactDurationsAndPreservesBothStereoContributions() {
        val fixtures = OpusCompatibilityFixtures.load()
        fixtures.forEach { fixture ->
            val metrics = OpusDecodedMetrics()
            NativeOpusDecoder().use { decoder ->
                fixture.packets.forEach { packet ->
                    val decoded = decoder.decode(packet, OpusPacketDecoder.MAX_FRAME_SIZE)
                    assertEquals("${fixture.producer}/${fixture.name}", fixture.samples, decoded.size)
                    metrics.add(decoded)
                }
            }
            if (fixture.dtxPackets > 0) {
                assertTrue("DTX RMS=${metrics.rms}", metrics.rms < 100)
            } else {
                if (fixture.leftHz > 0) assertTrue("${fixture.name} left=${metrics.tone1000}", metrics.tone1000 > 1500)
                if (fixture.rightHz > 0) assertTrue("${fixture.name} right=${metrics.tone2000}", metrics.tone2000 > 1500)
                if (fixture.channels == 2 && fixture.leftHz == 0) assertTrue(metrics.tone1000 < 500)
                if (fixture.channels == 2 && fixture.rightHz == 0) assertTrue(metrics.tone2000 < 500)
            }
        }
        println("A4A decoded ${fixtures.size} checked synthetic corpora with ${NativeOpusInfo.version}")
    }

    @Test fun voiceMusicStereoAndMonoSwitchWithinOneProductionTalker() {
        val fixtures = OpusCompatibilityFixtures.load().filter { it.producer == "libopus 1.3.1" }
        val names = listOf("voice-mono-20", "music-stereo-both-20", "music-stereo-right-20", "music-mono-20")
        val decoded = AtomicLong()
        TalkerJitterPipeline(decoded).use { pipeline ->
            names.forEachIndexed { index, name ->
                val fixture = fixtures.single { it.name == name }
                fixture.packets.take(2).forEachIndexed { packetIndex, packet ->
                    pipeline.offer(QueuedFrame(VoiceFrame(1, index * 2 + packetIndex, fixture.codec, packet, false), 0L))
                }
            }
            var delivered = 0
            var nonzero = false
            repeat(20) { tick ->
                pipeline.fillPcm(60_000_000L + tick * 10_000_000L)
                val pcm = pipeline.readPcm(480)
                delivered += pcm.size
                nonzero = nonzero || pcm.any { it.toInt() != 0 }
            }
            assertEquals(8L, decoded.get())
            assertEquals(8 * 960, delivered)
            assertTrue(nonzero)
            assertEquals(0, pipeline.readPcm(480).size)
        }
    }

    @Test fun malformedPacketsSmallBuffersAndReleasedHandlesFailClearly() {
        val packet = OpusCompatibilityFixtures.load().first { it.name == "voice-mono-20" }.packets.first()
        repeat(20) {
            val decoder = NativeOpusDecoder()
            for (invalid in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(3, 0), byteArrayOf(3, 63))) {
                assertThrows(IllegalArgumentException::class.java) { decoder.decode(invalid, 5760) }
                decoder.reset()
            }
            assertThrows(IllegalArgumentException::class.java) { decoder.decode(packet, 120) }
            decoder.reset()
            assertEquals(960, decoder.decode(packet, 5760).size)
            decoder.close()
            decoder.close()
            assertThrows(IllegalStateException::class.java) { decoder.decode(packet, 960) }
            assertThrows(IllegalStateException::class.java) { decoder.reset() }
        }
        val encoder = NativeOpusEncoder()
        assertThrows(IllegalArgumentException::class.java) { encoder.encode(ShortArray(479)) }
        encoder.close()
        encoder.close()
        assertThrows(IllegalStateException::class.java) { encoder.encode(ShortArray(960)) }
    }

    @Test fun nonemptyDtxPacketsAdvanceProductionDecoderWithoutBecomingEndMarkersOrPlc() {
        val fixture = OpusCompatibilityFixtures.load().first { it.producer == "libopus 1.3.1" && it.dtxPackets > 0 }
        var plcCalls = 0
        var packets = 0
        val count = AtomicLong()
        val factory = {
            val native = NativeOpusDecoder()
            object : OpusPacketDecoder {
                override fun packetInfo(packet: ByteArray) = native.packetInfo(packet)

                override fun decodeFec(
                    packet: ByteArray,
                    frameSize: Int,
                ) = native.decodeFec(packet, frameSize)

                override fun decode(
                    packet: ByteArray?,
                    frameSize: Int,
                ): ShortArray {
                    if (packet == null) plcCalls++ else packets++
                    return native.decode(packet, frameSize)
                }

                override fun reset() = native.reset()

                override fun close() = native.close()
            }
        }
        TalkerJitterPipeline(count, factory).use { pipeline ->
            fixture.packets.forEachIndexed { index, packet ->
                pipeline.offer(QueuedFrame(VoiceFrame(1, index, fixture.codec, packet, false), 0L))
                pipeline.fillPcm(60_000_000L + index * 20_000_000L)
                assertEquals(480, pipeline.readPcm(480).size)
                assertEquals(480, pipeline.readPcm(480).size)
            }
            assertEquals(fixture.packets.size, packets)
            assertEquals(fixture.packets.size.toLong(), count.get())
            assertEquals(0, plcCalls)
            assertEquals(0, pipeline.readPcm(480).size)
        }
    }
}
