package io.github.ts3mobile.audio.opus

/** Gain and bounded onset selection for complete 20 ms, post-denoise blocks. */
internal class MicrophoneFrameProcessor {
    private var options: MicrophoneProcessingOptions? = null
    private var gate = VolumeGate()
    private val gain = GainEnvelope()
    private val limiter = PcmLimiter()
    private val onset = ArrayDeque<ShortArray>()
    var isSpeaking = false
        private set

    fun process(
        pcm: ShortArray,
        current: MicrophoneProcessingOptions,
    ): List<ShortArray> = processSamples(pcm.size, current) { pcm[it].toFloat() }

    fun process(
        pcm: FloatArray,
        current: MicrophoneProcessingOptions,
    ): List<ShortArray> {
        require(pcm.all { it.isFinite() })
        return processSamples(pcm.size, current) { pcm[it] }
    }

    private inline fun processSamples(
        sampleCount: Int,
        current: MicrophoneProcessingOptions,
        sampleAt: (Int) -> Float,
    ): List<ShortArray> {
        require(sampleCount == 960)
        if (options != current) {
            val previous = options
            val volumeOnly = current.sameExceptVolume(previous)
            if (volumeOnly && current.settings.volumePercent > 0 && previous != null && previous.settings.volumePercent > 0) {
                onset.clear()
            } else {
                reset(preserveLimiter = options != null && current.settings.volumePercent > 0)
            }
            options = current
        }
        gain.setTarget(current.settings.volumePercent / 100f)
        val amplified = FloatArray(sampleCount) { sampleAt(it) * gain.next() }
        val limited = limiter.process(amplified)
        if (!current.volumeGate) {
            isSpeaking = true
            return listOf(limited)
        }
        val wasSpeaking = isSpeaking
        isSpeaking = gate.update(VolumeGate.rmsDb(amplified), current.settings.gateThresholdDb)
        if (wasSpeaking) {
            // Include the final hold block; the transport drains it before ending.
            return listOf(limited)
        }
        if (onset.size == ONSET_FRAMES) onset.removeFirst()
        onset.addLast(limited)
        return if (isSpeaking) onset.toList().also { onset.clear() } else emptyList()
    }

    fun reset(preserveLimiter: Boolean = false) {
        options = null
        gate = VolumeGate()
        onset.clear()
        if (!preserveLimiter) limiter.reset()
        isSpeaking = false
    }

    companion object {
        const val ONSET_FRAMES = 6
    }
}
