package io.github.ts3mobile.audio.opus

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

internal class PcmLimiter(sampleRate: Int = 48_000) {
    init {
        require(sampleRate > 0)
    }

    private val releaseCoefficient = exp(-1.0 / (sampleRate * 0.080))
    private val holdSamples = (sampleRate * 0.005).toInt().coerceAtLeast(1)
    private var gain = 1.0
    private var holdRemaining = 0

    fun process(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { index ->
            val sample = samples[index]
            require(sample.isFinite())
            val magnitude = abs(sample)
            val requiredGain = if (magnitude > CEILING) CEILING.toDouble() / magnitude else 1.0
            if (requiredGain <= gain) {
                gain = requiredGain
                holdRemaining = holdSamples
            } else if (holdRemaining > 0) {
                holdRemaining--
            } else {
                gain = 1.0 - (1.0 - gain) * releaseCoefficient
                gain = minOf(gain, requiredGain)
            }
            (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

    fun reset() {
        gain = 1.0
        holdRemaining = 0
    }

    fun advanceSilence(sampleCount: Int) {
        require(sampleCount >= 0)
        val held = minOf(sampleCount, holdRemaining)
        holdRemaining -= held
        gain = 1.0 - (1.0 - gain) * releaseCoefficient.pow(sampleCount - held)
    }

    companion object {
        val CEILING = (32_768.0 * 10.0.pow(-1.0 / 20.0)).toInt().toFloat()
    }
}
