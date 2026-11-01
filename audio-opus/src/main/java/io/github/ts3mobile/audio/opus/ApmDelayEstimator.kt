package io.github.ts3mobile.audio.opus

import kotlin.math.abs
import kotlin.math.roundToInt

internal class ApmDelayEstimator {
    data class Estimate(val milliseconds: Int?, val quality: AudioTimingQuality, val discontinuity: Boolean = false)

    private var previous: Double? = null
    private var lastValidAt = Long.MIN_VALUE

    fun estimate(
        render: RenderTiming?,
        reversedAt: Long,
        captureAt: Long?,
        processedAt: Long,
    ): Estimate {
        if (render == null || captureAt == null || render.quality == AudioTimingQuality.UNAVAILABLE) {
            val last = previous
            return if (last != null && processedAt - lastValidAt in 0L..ApmPolicy.ESTIMATE_GRACE_NANOS) {
                Estimate(last.roundToInt(), AudioTimingQuality.ESTIMATED)
            } else {
                Estimate(null, AudioTimingQuality.UNAVAILABLE)
            }
        }
        val captureAge = processedAt - captureAt
        val delay = (render.presentationNanos - reversedAt + captureAge) / 1_000_000.0
        if (captureAge !in 0L..ApmPolicy.MAX_REFERENCE_AGE_NANOS ||
            processedAt - reversedAt !in 0L..ApmPolicy.MAX_REFERENCE_AGE_NANOS || delay !in 0.0..ApmPolicy.MAX_DELAY_MS.toDouble()
        ) {
            reset()
            return Estimate(null, AudioTimingQuality.UNAVAILABLE, true)
        }
        val last = previous
        if (last != null && abs(delay - last) > ApmPolicy.DELAY_JUMP_MS) {
            reset()
            return Estimate(null, AudioTimingQuality.UNAVAILABLE, true)
        }
        val smoothed =
            if (last == null) {
                delay
            } else {
                last + (delay - last).coerceIn(-ApmPolicy.DELAY_STEP_MS, ApmPolicy.DELAY_STEP_MS) * ApmPolicy.DELAY_SMOOTHING
            }
        previous = smoothed
        lastValidAt = processedAt
        return Estimate(smoothed.roundToInt(), render.quality)
    }

    fun reset() {
        previous = null
        lastValidAt = Long.MIN_VALUE
    }
}
