package io.github.ts3mobile.audio.opus

internal object ApmPolicy {
    const val REFERENCE_CAPACITY_BLOCKS = 50
    const val MAX_REFERENCE_BLOCKS_PER_TICK = 5
    const val MAX_REFERENCE_AGE_NANOS = 500_000_000L
    const val ESTIMATE_GRACE_NANOS = 100_000_000L
    const val MAX_DELAY_MS = 500
    const val DELAY_JUMP_MS = 40.0
    const val DELAY_STEP_MS = 10.0
    const val DELAY_SMOOTHING = 0.25
    const val MAX_ESTIMATED_FRAMES = 10
    const val MAX_CLOCK_FAILURES = 10
    const val MAX_REFERENCE_FAILURES = 3
    const val MAX_PROCESS_NANOS = 5_000_000L
    const val MAX_CONSECUTIVE_OVERRUNS = 3
}
