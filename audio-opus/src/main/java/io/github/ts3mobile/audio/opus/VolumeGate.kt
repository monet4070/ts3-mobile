package io.github.ts3mobile.audio.opus

import kotlin.math.log10
import kotlin.math.sqrt

/** One update per 20 ms; queue readiness is deliberately unrelated to this state. */
internal class VolumeGate {
    var isOpen = false
        private set
    private var attackFrames = 0
    private var quietFrames = 0

    fun update(
        levelDb: Double,
        thresholdDb: Int,
    ): Boolean {
        if (!isOpen) {
            attackFrames = if (levelDb >= thresholdDb) attackFrames + 1 else 0
            if (attackFrames >= 2) {
                isOpen = true
                quietFrames = 0
            }
        } else {
            quietFrames = if (levelDb < thresholdDb - 6) quietFrames + 1 else 0
            if (quietFrames >= 15) {
                isOpen = false
                attackFrames = 0
            }
        }
        return isOpen
    }

    companion object {
        fun rmsDb(pcm: FloatArray): Double {
            require(pcm.isNotEmpty())
            val sumSquares =
                pcm.sumOf { sample ->
                    require(sample.isFinite())
                    val saturated = sample.coerceIn(-32768f, 32767f).toInt().toDouble()
                    saturated * saturated
                }
            return levelDb(sumSquares, pcm.size)
        }

        private fun levelDb(
            sumSquares: Double,
            sampleCount: Int,
        ): Double {
            val rms = sqrt(sumSquares / sampleCount) / 32768.0
            return if (rms == 0.0) -96.0 else (20.0 * log10(rms)).coerceAtLeast(-96.0)
        }
    }
}
