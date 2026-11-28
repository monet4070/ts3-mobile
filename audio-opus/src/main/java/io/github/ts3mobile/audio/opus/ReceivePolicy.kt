package io.github.ts3mobile.audio.opus

enum class ReceiveDelayProfile(
    internal val initialMs: Int,
    internal val minMs: Int,
    internal val maxMs: Int,
    internal val diagnosticCode: Long,
) {
    BALANCED(60, 40, 120, 0),
    STABILITY(90, 70, 160, 1),
}

internal data class ReceivePolicy(
    val adaptive: Boolean = true,
    val confirmedPacketSamples: Int? = null,
    val delayProfile: ReceiveDelayProfile = ReceiveDelayProfile.BALANCED,
) {
    init {
        require(confirmedPacketSamples == null || confirmedPacketSamples in setOf(480, 960))
    }

    companion object {
        const val INPUT_AGE_NANOS = 60_000_000L
        const val MAX_CLIENT_SAMPLES = 11_520
        const val MAX_PACKET_BYTES = 61_440
        const val MAX_CLIENT_BYTES = 122_880
        const val MAX_GLOBAL_BYTES = 524_288
        const val MAX_CLIENT_PACKETS = 96
        const val MAX_TRACKED_CLIENTS = 64
        const val MAX_INPUT_PACKETS = 256
        const val JITTER_WINDOW = 64
        const val STABLE_PACKETS = 128
        const val MAX_PLC_SAMPLES = 2_880
        const val PCM_FIFO_CAPACITY_SAMPLES = MAX_CLIENT_SAMPLES
        const val TARGET_STEP_MS = 10
        const val JITTER_PERCENTILE_INDEX = 60
        const val JITTER_MULTIPLIER = 2
        const val INCREASE_EVERY_PACKETS = 16
        const val LATE_PACKETS_TO_INCREASE = 2
        const val FADE_SAMPLES = 240
        const val TALKER_IDLE_NANOS = 2_000_000_000L
        const val MAX_TALKER_MEDIA_AGE_NANOS = 360_000_000L
        const val DEDUPLICATION_HISTORY_PACKETS = 128
    }
}

internal data class OpusPacketInfo(val samples: Int, val hasFec: Boolean = false, val timingUncertain: Boolean = false) {
    init {
        require(samples in 120..5760 && samples % 120 == 0)
    }
}
