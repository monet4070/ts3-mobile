package io.github.ts3mobile.audio.opus

import java.util.concurrent.atomic.AtomicLong

internal class ReceiveStats {
    val stalePackets = AtomicLong()
    val invalidPackets = AtomicLong()
    val budgetResets = AtomicLong()
    val duplicates = AtomicLong()
    val latePackets = AtomicLong()
    val plcPackets = AtomicLong()
    val fecAttempts = AtomicLong()
    val fecFailures = AtomicLong()
    val overflowDroppedPcmSamples = AtomicLong()
    val focusDenials = AtomicLong()
    val outputFailures = AtomicLong()
    val outputRecoveries = AtomicLong()
    val outputRecoveryExhausted = AtomicLong()
    val drainTimeouts = AtomicLong()
    val rebuffers = AtomicLong()
    val maxInputAgeNanos = AtomicLong()
    val maxTalkerAgeNanos = AtomicLong()
    val maxCombinedWaitNanos = AtomicLong()

    fun summaryLine(): String =
        "TS3_AUDIO: receive stale=${stalePackets.get()} invalid=${invalidPackets.get()} budget=${budgetResets.get()} " +
            "duplicates=${duplicates.get()} late=${latePackets.get()} plc=${plcPackets.get()} " +
            "fec=${fecAttempts.get()} fecFailed=${fecFailures.get()} rebuffers=${rebuffers.get()} " +
            "pcmDropped=${overflowDroppedPcmSamples.get()} focusDenied=${focusDenials.get()} " +
            "outputFailed=${outputFailures.get()} recovered=${outputRecoveries.get()} exhausted=${outputRecoveryExhausted.get()} " +
            "drainTimeouts=${drainTimeouts.get()} maxInputNs=${maxInputAgeNanos.get()} " +
            "maxTalkerNs=${maxTalkerAgeNanos.get()} maxCombinedNs=${maxCombinedWaitNanos.get()}"

    fun maximum(
        counter: AtomicLong,
        value: Long,
    ) {
        counter.accumulateAndGet(value.coerceAtLeast(0), ::maxOf)
    }
}
