package io.github.ts3mobile.audio.opus

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

internal object AudioSignalMetrics {
    data class Levels(val rmsDbfs: Double, val peakDbfs: Double, val clippedSamples: Int)

    fun levels(samples: ShortArray): Levels {
        require(samples.isNotEmpty())
        val normalized = samples.map { it.toDouble() / 32_768.0 }
        val rms = sqrt(normalized.sumOf { it * it } / samples.size)
        val peak = normalized.maxOf { abs(it) }
        return Levels(
            20 * log10(rms),
            20 * log10(peak),
            samples.count { it == Short.MIN_VALUE || it == Short.MAX_VALUE },
        )
    }

    fun toneAmplitude(
        samples: ShortArray,
        frequencyHz: Double,
    ): Double {
        require(samples.isNotEmpty())
        var real = 0.0
        var imaginary = 0.0
        samples.forEachIndexed { index, sample ->
            val phase = 2 * PI * frequencyHz * index / 48_000
            real += sample * cos(phase)
            imaginary -= sample * sin(phase)
        }
        return 2 * sqrt(real * real + imaginary * imaginary) / samples.size
    }

    fun delaySamples(
        reference: ShortArray,
        observed: ShortArray,
        maximumLag: Int,
    ): Int {
        require(maximumLag >= 0)
        val comparedSamples = minOf(reference.size, observed.size) - maximumLag
        require(comparedSamples > 0)
        return (-maximumLag..maximumLag).maxBy { lag ->
            val start = maxOf(0, -lag)
            (start until start + comparedSamples).sumOf { index ->
                reference[index].toDouble() * observed[index + lag]
            }
        }
    }
}
