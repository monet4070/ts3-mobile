package io.github.ts3mobile.audio.opus

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Aggregates one capture session's measurements: denoise timing, VAD
 * probability, input/output energy and frame counts. Pure bookkeeping with
 * no Android dependencies so it is unit-testable; the summary is printed by
 * the caller at session end. Extracted from OpusMicrophoneCapture.
 */
internal class CaptureSessionStats {
    private val capturedNonZeroPcm = AtomicBoolean(false)
    private var denoisedFrameCount = 0L
    private var totalDenoiseNanos = 0L
    private var maxDenoiseNanos = 0L
    private var vadTotal = 0.0
    private var inputEnergy = 0.0
    private var outputEnergy = 0.0
    private var measuredSamples = 0L

    fun noteRawFrame(pcm: ShortArray) {
        var rawNonZero = false
        pcm.forEach { sample ->
            val value = sample.toDouble()
            inputEnergy += value * value
            if (sample.toInt() != 0) rawNonZero = true
        }
        if (rawNonZero) capturedNonZeroPcm.set(true)
    }

    fun noteDenoisedFrame(
        pcm: ShortArray,
        denoiseNanos: Long,
        vadProbability: Float,
    ) {
        totalDenoiseNanos += denoiseNanos
        maxDenoiseNanos = max(maxDenoiseNanos, denoiseNanos)
        vadTotal += vadProbability
        pcm.forEach { sample ->
            val value = sample.toDouble()
            outputEnergy += value * value
        }
        measuredSamples += pcm.size
        denoisedFrameCount++
    }

    fun capturedNonZero(): Boolean = capturedNonZeroPcm.get()

    /** The sanitized single-line summary logged when a session ends. */
    fun summaryLine(
        encodedCount: Long,
        providedCount: Long,
    ): String {
        if (encodedCount <= 0L) return ""
        val averageDenoiseMicros =
            if (denoisedFrameCount == 0L) {
                0L
            } else {
                totalDenoiseNanos / denoisedFrameCount / 1_000L
            }
        val inputRms =
            if (measuredSamples == 0L) {
                0
            } else {
                sqrt(inputEnergy / measuredSamples).toInt()
            }
        val outputRms =
            if (measuredSamples == 0L) {
                0
            } else {
                sqrt(outputEnergy / measuredSamples).toInt()
            }
        val averageVad =
            if (denoisedFrameCount == 0L) {
                0.0
            } else {
                vadTotal / denoisedFrameCount
            }
        return "TS3_AUDIO: microphone session ended " +
            "(encoded=$encodedCount, provided=$providedCount, " +
            "nonZero=${capturedNonZeroPcm.get()}, " +
            "rnnoiseAvg=${averageDenoiseMicros}us, " +
            "rnnoiseMax=${maxDenoiseNanos / 1_000L}us, " +
            "inputRms=$inputRms, outputRms=$outputRms, " +
            "vad=${"%.3f".format(averageVad)})"
    }
}
