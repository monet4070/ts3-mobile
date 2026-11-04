package io.github.ts3mobile.audio.opus

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NativeApmExperimentTest {
    @Test fun concurrentCloseCannotReleaseProcessorDuringNativeCalls() {
        val processor = NativeApmProcessor(false, false)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val processing =
                executor.submit {
                    check(start.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    repeat(200) {
                        try {
                            processor.reverse(FloatArray(480))
                            processor.capture(FloatArray(480), 0)
                            processor.reset()
                        } catch (_: IllegalStateException) {
                            // Close may win before the next synchronized call.
                        }
                    }
                }
            val closing =
                executor.submit {
                    check(start.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    processor.close()
                }
            start.countDown()
            processing.get(10, java.util.concurrent.TimeUnit.SECONDS)
            closing.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertThrows(IllegalStateException::class.java) { processor.reverse(FloatArray(480)) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            processor.close()
        }
    }

    @Before fun requireExplicitExperimentBuild() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("apmExperiment") == "true")
    }

    @Test fun disabledProcessingPreservesFloatPcmAndDoesNotApplyAutomaticGain() {
        val random = Random(24)
        NativeApmProcessor(false, false).use { processor ->
            repeat(100) {
                val input = FloatArray(480) { random.nextFloat() * 0.04f - 0.02f }
                processor.reverse(FloatArray(480))
                val output = processor.capture(input, 0)
                assertArrayEquals(input, output, 0.000001f)
            }
        }
    }

    @Test fun aecReducesDelayedEchoPreservesDoubleTalkAndReadaptsAfterDelayChange() {
        val random = Random(24)
        val far = FloatArray(16 * 48_000) { random.nextFloat() * 0.4f - 0.2f }
        val processed = FloatArray(far.size)
        val captured = FloatArray(far.size)
        val elapsed = LongArray(1_600)
        NativeApmProcessor(true, false).use { processor ->
            repeat(1_600) { frame ->
                val start = frame * 480
                val acousticDelay = if (frame < 1_200) 2_880 else 4_800
                val input =
                    FloatArray(480) { index ->
                        val position = start + index
                        val echo = if (position >= acousticDelay) far[position - acousticDelay] * 0.5f else 0f
                        val near = if (frame in 800..1199) (sin(2 * PI * 733 * position / 48_000) * 0.08).toFloat() else 0f
                        (echo + near).also { captured[position] = it }
                    }
                val render = far.copyOfRange(start, start + 480)
                val began = System.nanoTime()
                processor.reverse(render)
                val output = processor.capture(input, 0)
                elapsed[frame] = System.nanoTime() - began
                assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
                output.copyInto(processed, start)
            }
        }
        val initialErle = erle(captured, processed, 6 * 48_000, 8 * 48_000)
        val changedErle = erle(captured, processed, 14 * 48_000, 16 * 48_000)
        val nearAmplitude = toneAmplitude(processed, 10 * 48_000, 12 * 48_000, 733.0)
        val timings = elapsed.drop(100).sorted()
        println(
            "A3 synthetic: ERLE60ms=$initialErle dB, ERLE100ms=$changedErle dB, doubleTalk733Hz=$nearAmplitude, " +
                "reverse+capture P95=${timings[timings.size * 95 / 100]} ns, P99=${timings[timings.size * 99 / 100]} ns",
        )
        assertTrue("60 ms echo ERLE=$initialErle", initialErle > 6.0)
        assertTrue("100 ms echo ERLE=$changedErle", changedErle > 6.0)
        assertTrue("Near-end amplitude=$nearAmplitude", nearAmplitude > 0.04 && nearAmplitude < 0.12)
    }

    @Test fun noiseSuppressionAttenuatesStationaryNoiseWithoutRaisingSilence() {
        val random = Random(25)
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        NativeApmProcessor(false, true).use { processor ->
            repeat(400) { frame ->
                val input = FloatArray(480) { random.nextFloat() * 0.05f - 0.025f }
                processor.reverse(FloatArray(480))
                val output = processor.capture(input, 0)
                if (frame >= 300) {
                    inputEnergy += input.sumOf { it.toDouble() * it }
                    outputEnergy += output.sumOf { it.toDouble() * it }
                }
            }
            processor.reset()
            repeat(20) {
                processor.reverse(FloatArray(480))
                assertTrue(processor.capture(FloatArray(480), 0).all { abs(it) < 0.0001f })
            }
        }
        val reduction = 10 * log10(inputEnergy / outputEnergy)
        println("A3 synthetic stationary-noise reduction=$reduction dB")
        assertTrue(reduction > 3.0)
    }

    @Test fun captureDelayIsBoundedAndResetRemovesPreviousEchoState() {
        val random = Random(26)
        val input = FloatArray(48_000) { random.nextFloat() * 0.1f - 0.05f }
        val output = FloatArray(input.size)
        NativeApmProcessor(true, false).use { processor ->
            repeat(100) { frame ->
                processor.reverse(FloatArray(480))
                processor.capture(input.copyOfRange(frame * 480, frame * 480 + 480), 0).copyInto(output, frame * 480)
            }
            processor.reset()
            repeat(10) {
                processor.reverse(FloatArray(480))
                assertTrue(processor.capture(FloatArray(480), 0).all { abs(it) < 0.001f })
            }
        }
        val lag =
            (0..480).maxBy { shift ->
                (24_000 until 47_520).sumOf { input[it].toDouble() * output[it + shift] }
            }
        println("A3 synthetic near-end algorithm delay=$lag samples")
        assertTrue(lag < 480)
    }

    @Test fun realApmFloatOutputUsesExistingManualGainAndGateBeforeEncoding() {
        val reference = RenderReferenceBuffer()
        val frames = MicrophoneFrameProcessor()
        val options = MicrophoneProcessingOptions(MicrophoneSettings(volumePercent = 200, noiseSuppression = false), true)
        var now = 100_000_000L
        val worker =
            ApmExperimentWorker(
                reference,
                ApmExperimentPolicy(ApmRoute.BUILTIN, true, true),
                { NativeApmProcessor(false, false) },
                { error("Unexpected synthetic fallback: $it") },
                {},
                { now },
            )
        worker.use {
            val accumulated = FloatArray(960)
            repeat(4) { half ->
                val processed = worker.process(ShortArray(480) { 250 }, now)!!
                for (index in processed.indices) accumulated[(half % 2) * 480 + index] = processed[index] * 32768f
                if (half == 1) assertTrue(frames.process(accumulated, options).isEmpty())
                if (half == 3) {
                    assertEquals(2, frames.process(accumulated, options).size)
                    assertTrue(frames.isSpeaking)
                }
                now += 10_000_000L
            }
            assertEquals(ApmFallbackReason.NONE, worker.status.reason)
        }
    }

    @Test fun invalidFramesAndClosedHandlesFailAndRepeatedLifetimesRemainSafe() {
        repeat(20) {
            val processor = NativeApmProcessor()
            assertThrows(IllegalArgumentException::class.java) { processor.reverse(FloatArray(479)) }
            assertThrows(IllegalArgumentException::class.java) { processor.capture(FloatArray(480), 501) }
            assertThrows(IllegalArgumentException::class.java) { processor.capture(FloatArray(480) { Float.NaN }, 0) }
            processor.close()
            processor.close()
            assertThrows(IllegalStateException::class.java) { processor.reset() }
        }
        assertFalse(ApmExperimentPolicy(ApmRoute.BLUETOOTH, true, true).eligible)
    }

    @Test fun experimentalFloatPipelineFeedsRealOpusWithoutCallingTheRnnoiseBaseline() {
        val reference = RenderReferenceBuffer()
        val settings = MicrophoneSettings()
        var now = 100_000_000L
        var decodedFrames = 0
        var hasSignal = false
        NativeOpusEncoder().use { encoder ->
            NativeOpusDecoder().use { decoder ->
                val worker =
                    ApmExperimentWorker(
                        reference,
                        ApmExperimentPolicy(ApmRoute.BUILTIN, true, true),
                        { NativeApmProcessor() },
                        { error("Unexpected baseline fallback: $it") },
                        {},
                        { now },
                    )
                ApmCapturePipeline(worker, settings, encoder::encode).use { pipeline ->
                    repeat(80) { half ->
                        val input =
                            ShortArray(480) { index ->
                                (sin(2 * PI * 733 * (half * 480 + index) / 48_000) * 5000).toInt().toShort()
                            }
                        pipeline.process(input, now, MicrophoneProcessingOptions(settings), 0)?.forEach { packet ->
                            val decoded = decoder.decode(packet, 960)
                            assertEquals(960, decoded.size)
                            hasSignal = hasSignal || decoded.any { it.toInt() != 0 }
                            decodedFrames++
                        }
                        now += 10_000_000
                    }
                }
            }
        }
        assertEquals(40, decodedFrames)
        assertTrue(hasSignal)
    }

    @Test fun combinedAecAndNsDelayIsComparedWithTheExistingRnnoiseBaseline() {
        val random = Random(27)
        val input = FloatArray(48_000) { random.nextFloat() * 0.2f - 0.1f }
        val candidate = FloatArray(input.size)
        val baseline = FloatArray(input.size)
        val elapsed = LongArray(100)
        NativeApmProcessor(true, true).use { processor ->
            repeat(100) { frame ->
                val start = frame * 480
                val samples = input.copyOfRange(start, start + 480)
                val began = System.nanoTime()
                processor.reverse(FloatArray(480))
                processor.capture(samples, 0).copyInto(candidate, start)
                elapsed[frame] = System.nanoTime() - began
            }
        }
        NativeRnNoiseProcessor().use { processor ->
            repeat(100) { frame ->
                val start = frame * 480
                val samples = ShortArray(480) { (input[start + it] * 32768).toInt().toShort() }
                processor.processInPlace(samples)
                for (index in samples.indices) baseline[start + index] = samples[index] / 32768f
            }
        }

        fun lag(output: FloatArray) =
            (0..1440).maxBy { shift ->
                (24_000 until 46_560).sumOf { input[it].toDouble() * output[it + shift] }
            }
        val apmDelay = lag(candidate)
        val rnnoiseDelay = lag(baseline)
        val timings = elapsed.drop(10).sorted()
        println(
            "A3 synthetic combined AEC+NS delay=$apmDelay samples, RNNoise baseline=$rnnoiseDelay samples, " +
                "extra=${apmDelay - rnnoiseDelay} samples, combined P95=${timings[timings.size * 95 / 100]} ns, " +
                "P99=${timings[timings.size * 99 / 100]} ns",
        )
        assertTrue(apmDelay < 1440 && rnnoiseDelay < 1440)
        assertTrue(apmDelay - rnnoiseDelay <= 480)
        assertTrue(candidate.any { abs(it) > 0.00001f })
        assertTrue(baseline.any { abs(it) > 0.00001f })
    }

    private fun erle(
        input: FloatArray,
        output: FloatArray,
        start: Int,
        end: Int,
    ): Double {
        val inputEnergy = (start until end).sumOf { input[it].toDouble() * input[it] }
        val outputEnergy = (start until end).sumOf { output[it].toDouble() * output[it] }
        return 10 * log10(inputEnergy / outputEnergy)
    }

    private fun toneAmplitude(
        input: FloatArray,
        start: Int,
        end: Int,
        frequency: Double,
    ): Double {
        var real = 0.0
        var imaginary = 0.0
        for (index in start until end) {
            val phase = 2 * PI * frequency * index / 48_000
            real += input[index] * cos(phase)
            imaginary += input[index] * sin(phase)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / (end - start)
    }
}
