package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeAudioLifecycleTest {
    @Test fun volumeChangesPreserveRealDenoiserHistoryAgainstUnchangedControl() {
        NativeRnNoiseProcessor().use { denoiser ->
            NativeRnNoiseProcessor().use { control ->
                var configurations = 0
                var encoded = 0
                var actual = ShortArray(0)
                val pipeline =
                    MicrophoneCapturePipeline({ pcm ->
                        denoiser.processInPlace(pcm).also { actual = pcm.copyOf() }
                    }, {
                        encoded++
                        byteArrayOf(1)
                    }, CaptureSessionStats(), {
                        configurations++
                        denoiser.reset()
                    })
                repeat(160) { frame ->
                    val input =
                        ShortArray(480) { index ->
                            val position = frame * 480 + index
                            val phase = 2 * Math.PI * 130 * position / 48_000
                            (10000 * (kotlin.math.sin(phase) + 0.4 * kotlin.math.sin(2 * phase))).toInt().toShort()
                        }
                    val expected = input.copyOf()
                    control.processInPlace(expected)
                    val options = MicrophoneProcessingOptions(MicrophoneSettings(volumePercent = 100 + frame % 50))
                    pipeline.process(input, options, frame.toLong(), 0)
                    assertArrayEquals(expected, actual)
                }
                assertEquals(1, configurations)
                assertEquals(80, encoded)
            }
        }
    }

    @Test fun codecAndDenoiserCallsAreSerializedWithConcurrentClose() {
        val encoder = NativeOpusEncoder()
        val packet = encoder.encode(ShortArray(960) { 1000 })
        raceClose(encoder) { encoder.encode(ShortArray(960)) }
        val decoder = NativeOpusDecoder()
        decoder.decode(packet, 5760)
        raceClose(decoder) {
            decoder.decode(packet, 5760)
            decoder.reset()
            decoder.decodeFec(packet, 960)
        }
        val denoiser = NativeRnNoiseProcessor()
        denoiser.processInPlace(ShortArray(480))
        raceClose(denoiser) { denoiser.processInPlace(ShortArray(480)) }
    }

    private fun raceClose(
        resource: AutoCloseable,
        operation: () -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val calls =
                executor.submit {
                    check(start.await(3, TimeUnit.SECONDS))
                    repeat(200) {
                        try {
                            operation()
                        } catch (_: IllegalStateException) {
                            // Concurrent close is a documented terminal state.
                        }
                    }
                }
            val closing =
                executor.submit {
                    check(start.await(3, TimeUnit.SECONDS))
                    resource.close()
                    resource.close()
                }
            start.countDown()
            calls.get(10, TimeUnit.SECONDS)
            closing.get(10, TimeUnit.SECONDS)
            assertThrows(IllegalStateException::class.java) { operation() }
        } finally {
            start.countDown()
            executor.shutdownNow()
            resource.close()
        }
    }
}
