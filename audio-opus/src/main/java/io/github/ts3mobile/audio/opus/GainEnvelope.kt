package io.github.ts3mobile.audio.opus

internal class GainEnvelope(sampleRate: Int = 48_000) {
    init {
        require(sampleRate > 0)
    }

    private val rampSamples = (sampleRate * 0.020).toInt().coerceAtLeast(1)
    private var initialized = false
    private var current = 0f
    private var target = 0f
    private var step = 0f
    private var remaining = 0

    fun setTarget(gain: Float) {
        require(gain.isFinite() && gain in 0f..2f)
        if (!initialized || gain == 0f) {
            initialized = true
            current = gain
            target = gain
            remaining = 0
        } else if (gain != target) {
            target = gain
            remaining = rampSamples
            step = (target - current) / rampSamples
        }
    }

    fun next(): Float {
        check(initialized)
        if (remaining > 0) {
            remaining--
            current = if (remaining == 0) target else current + step
        }
        return current
    }
}
