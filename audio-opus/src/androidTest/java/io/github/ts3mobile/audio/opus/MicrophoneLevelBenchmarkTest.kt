package io.github.ts3mobile.audio.opus

import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic, offline timing/allocation probe; no AudioRecord, speaker or network. */
class MicrophoneLevelBenchmarkTest {
    private data class Measurement(val p95Nanos: Long, val allocations: Int, val encodedFrames: Int)

    @Test fun observingKeepsRealCodecProcessingWithinTheTwentyMillisecondBlockBudget() {
        val report = StringBuilder("Synthetic arm64 emulator, real Opus, 2000 blocks per condition\n")
        listOf(false, true).forEach { suppression ->
            val plain = run(observed = false, suppression = suppression)
            val observed = run(observed = true, suppression = suppression)
            assertEquals(plain.encodedFrames, observed.encodedFrames)
            assertEquals(2_000, observed.encodedFrames)
            assertTrue("Observed p95 exceeded 20ms audio block budget", observed.p95Nanos < 20_000_000)
            report.append("rnnoise=$suppression off: p95_ns=${plain.p95Nanos}, ")
                .append("thread_allocations=${plain.allocations}, encoded=${plain.encodedFrames}\n")
            report.append("rnnoise=$suppression on: p95_ns=${observed.p95Nanos}, ")
                .append("thread_allocations=${observed.allocations}, encoded=${observed.encodedFrames}\n")
        }
        report.append("No microphone/HAL scheduling or physical underrun claim.\n")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "microphone-level-benchmark.txt").writeText(report.toString())
    }

    @Suppress("DEPRECATION")
    private fun run(
        observed: Boolean,
        suppression: Boolean,
    ): Measurement =
        NativeRnNoiseProcessor().use { denoiser ->
            NativeOpusEncoder().use { encoder ->
                var encodedCount = 0
                val pipeline =
                    MicrophoneCapturePipeline(denoiser::processInPlace, {
                        encodedCount++
                        encoder.encode(it)
                    }, CaptureSessionStats(), {})
                val options = MicrophoneProcessingOptions(MicrophoneSettings(noiseSuppression = suppression))
                val input = ShortArray(480) { (sin(2 * PI * 1000 * it / 48000) * 5000).toInt().toShort() }
                val source = input.copyOf()
                val meter = MicrophoneLevelMeter()
                val subscription = if (observed) meter.subscribe() else null
                meter.activate(1, 0, 0)
                repeat(400) {
                    source.copyInto(input)
                    pipeline.process(input, options, 0, measureLevel = observed)
                    source.copyInto(input)
                    pipeline.process(input, options, 0, measureLevel = observed)
                }
                encodedCount = 0
                val durations = LongArray(2_000)
                Debug.startAllocCounting()
                val allocationsBefore = Debug.getThreadAllocCount()
                try {
                    durations.indices.forEach { index ->
                        val started = System.nanoTime()
                        source.copyInto(input)
                        pipeline.process(input, options, 0, measureLevel = meter.isObserved())
                        source.copyInto(input)
                        pipeline.process(input, options, 0, measureLevel = meter.isObserved())
                        if (observed) {
                            meter.offer(1, 0, 0, pipeline.levelDb, false, pipeline.isSpeaking, true, -40)
                        }
                        durations[index] = System.nanoTime() - started
                    }
                    val allocations = Debug.getThreadAllocCount() - allocationsBefore
                    durations.sort()
                    Measurement(durations[(durations.size * 0.95).toInt()], allocations, encodedCount)
                } finally {
                    Debug.stopAllocCounting()
                    subscription?.close()
                }
            }
        }
}
