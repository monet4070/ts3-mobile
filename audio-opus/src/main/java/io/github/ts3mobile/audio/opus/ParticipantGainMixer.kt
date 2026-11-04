package io.github.ts3mobile.audio.opus

internal class ParticipantGainMixer {
    data class Input(
        val clientId: Int,
        val samples: ShortArray,
        val gain: Float,
    )

    private val gains = mutableMapOf<Int, GainEnvelope>()
    private val limiter = PcmLimiter()

    fun mix(
        inputs: List<Input>,
        sampleCount: Int,
    ): ShortArray {
        require(sampleCount >= 0)
        require(inputs.map { it.clientId }.distinct().size == inputs.size)
        var hasSamples = false
        val mixed = FloatArray(sampleCount)
        for (input in inputs) {
            require(input.samples.size <= sampleCount)
            val envelope = gains.getOrPut(input.clientId) { GainEnvelope() }
            envelope.setTarget(input.gain)
            if (input.gain == 0f || input.samples.isEmpty()) continue
            hasSamples = true
            for (sampleIndex in mixed.indices) {
                val gain = envelope.next()
                if (sampleIndex < input.samples.size) mixed[sampleIndex] += input.samples[sampleIndex] * gain
            }
        }
        if (!hasSamples) {
            limiter.advanceSilence(sampleCount)
            return ShortArray(0)
        }
        return limiter.process(mixed)
    }

    fun forget(clientId: Int) {
        gains.remove(clientId)
    }

    fun advanceSilence(samples: Int) = limiter.advanceSilence(samples)

    fun reset() {
        gains.clear()
        limiter.reset()
    }
}
