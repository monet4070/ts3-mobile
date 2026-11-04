package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic PCM only; never constructs AudioRecord or opens a server. */
class MicrophonePipelineTest {
    @Test fun boostedSyntheticSignalIsLimitedBeforeRealOpusAndRevisionDropsOldTail() {
        NativeOpusEncoder().use { encoder ->
            NativeOpusDecoder().use { decoder ->
                val encodedPcm = mutableListOf<ShortArray>()
                val pipeline =
                    MicrophoneCapturePipeline({ error("Suppression is bypassed") }, {
                        encodedPcm += it.copyOf()
                        encoder.encode(it)
                    }, CaptureSessionStats(), {})
                val boosted = MicrophoneProcessingOptions(MicrophoneSettings(volumePercent = 200, noiseSuppression = false))
                var decodedFrames = 0
                repeat(40) { half ->
                    val input =
                        ShortArray(480) { index ->
                            (sin(2 * PI * 1000 * (half * 480 + index) / 48000) * 30000).toInt().toShort()
                        }
                    pipeline.process(input, boosted, 0)?.forEach { packet ->
                        assertEquals(960, decoder.decode(packet, 960).size)
                        decodedFrames++
                    }
                }
                assertEquals(20, decodedFrames)
                assertTrue(encodedPcm.all { block -> block.all { kotlin.math.abs(it.toInt()) <= PcmLimiter.CEILING.toInt() } })
                assertTrue(encodedPcm.any { block -> block.any { kotlin.math.abs(it.toInt()) > 29000 } })
                pipeline.process(ShortArray(480) { 30000 }, boosted, 0)
                repeat(2) { pipeline.process(ShortArray(480), boosted, 1) }
                assertTrue(encodedPcm.last().all { it.toInt() == 0 })
            }
        }
    }

    @Test fun syntheticPcmPassesRealOpusOnsetAndTailAndStopsDuringSilence() {
        NativeRnNoiseProcessor().use { denoiser ->
            NativeOpusEncoder().use { encoder ->
                NativeOpusDecoder().use { decoder ->
                    val queue = CaptureVoiceBuffer()
                    val pipeline = MicrophoneCapturePipeline(denoiser::processInPlace, encoder::encode, CaptureSessionStats(), {})
                    val options = MicrophoneProcessingOptions(MicrophoneSettings(volumePercent = 150, noiseSuppression = false), true)
                    var decodedNonZero = false
                    var decodedFrames = 0

                    fun drain() {
                        while (true) {
                            val packet = queue.poll() ?: break
                            val pcm = decoder.decode(packet, 960)
                            assertEquals(960, pcm.size)
                            decodedNonZero = decodedNonZero || pcm.any { it.toInt() != 0 }
                            decodedFrames++
                        }
                    }

                    fun feed(sound: Boolean) {
                        repeat(2) { half ->
                            val pcm =
                                ShortArray(480) { index ->
                                    if (sound) (sin(2 * PI * 440 * (index + half * 480) / 48000) * 5000).toInt().toShort() else 0
                                }
                            val packets = pipeline.process(pcm, options, queue.revision())
                            if (packets != null) queue.publish(queue.revision(), packets, pipeline.isSpeaking)
                        }
                    }
                    repeat(50) { feed(false) }
                    assertFalse(queue.isTransmitting())
                    assertEquals(0, decodedFrames)
                    feed(true)
                    assertFalse(queue.isTransmitting())
                    feed(true)
                    assertTrue(queue.isTransmitting())
                    drain()
                    assertEquals(6, decodedFrames)
                    assertTrue(queue.isTransmitting())
                    repeat(10) {
                        feed(true)
                        drain()
                    }
                    repeat(15) {
                        feed(false)
                        drain()
                    }
                    assertFalse(queue.isTransmitting())
                    val sent = decodedFrames
                    repeat(100) {
                        feed(false)
                        drain()
                    }
                    assertEquals(sent, decodedFrames)
                    assertTrue(decodedNonZero)
                    queue.stop()
                    assertFalse(queue.isTransmitting())
                }
            }
        }
    }

    @Test fun realRnnoiseIsUsedByProductionPipelineAndBypassLeavesPcmUnchanged() {
        NativeRnNoiseProcessor().use { denoiser ->
            var calls = 0
            val encoded = mutableListOf<ShortArray>()
            val pipeline =
                MicrophoneCapturePipeline({
                    calls++
                    denoiser.processInPlace(it)
                }, {
                    encoded += it.copyOf()
                    byteArrayOf(1)
                }, CaptureSessionStats(), {})
            repeat(100) { pipeline.process(ShortArray(480), MicrophoneProcessingOptions(), 0) }
            assertEquals(100, calls)
            val bypass = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = false))
            repeat(2) { pipeline.process(ShortArray(480) { 1000 }, bypass, 1) }
            assertEquals(100, calls)
            assertTrue(encoded.last().all { it.toInt() == 1000 })
        }
    }
}
