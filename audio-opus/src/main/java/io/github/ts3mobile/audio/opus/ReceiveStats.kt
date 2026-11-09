package io.github.ts3mobile.audio.opus

import java.util.concurrent.atomic.AtomicLong

internal class ReceiveStats {
    private val counts = Array(AudioReceiveCounter.entries.size) { AtomicLong() }
    private val peaks = Array(AudioReceivePeak.entries.size) { AtomicLong() }

    @Volatile var observer: AudioDiagnosticsObserver? = null

    @Volatile var actualRoute: AudioRouteKind? = null

    val stalePackets = counts[AudioReceiveCounter.STALE.ordinal]
    val invalidPackets = counts[AudioReceiveCounter.INVALID.ordinal]
    val budgetResets = counts[AudioReceiveCounter.BUDGET_RESETS.ordinal]
    val duplicates = counts[AudioReceiveCounter.DUPLICATES.ordinal]
    val latePackets = counts[AudioReceiveCounter.LATE.ordinal]
    val plcPackets = counts[AudioReceiveCounter.PLC.ordinal]
    val fecAttempts = counts[AudioReceiveCounter.FEC_ATTEMPTS.ordinal]
    val fecFailures = counts[AudioReceiveCounter.FEC_FAILURES.ordinal]
    val overflowDroppedPcmSamples = counts[AudioReceiveCounter.PCM_DROPPED.ordinal]
    val focusDenials = counts[AudioReceiveCounter.FOCUS_DENIALS.ordinal]
    val outputFailures = counts[AudioReceiveCounter.OUTPUT_FAILURES.ordinal]
    val outputRecoveries = counts[AudioReceiveCounter.OUTPUT_RECOVERIES.ordinal]
    val outputRecoveryExhausted = counts[AudioReceiveCounter.OUTPUT_EXHAUSTED.ordinal]
    val drainTimeouts = counts[AudioReceiveCounter.DRAIN_TIMEOUTS.ordinal]
    val rebuffers = counts[AudioReceiveCounter.REBUFFERS.ordinal]
    val maxInputAgeNanos = AtomicLong()
    val maxTalkerAgeNanos = AtomicLong()
    val maxCombinedWaitNanos = AtomicLong()

    fun count(
        key: AudioReceiveCounter,
        amount: Long = 1,
    ) {
        counts[key.ordinal].addAndGet(amount.coerceAtLeast(0))
    }

    fun peak(
        key: AudioReceivePeak,
        value: Long,
    ) {
        peaks[key.ordinal].accumulateAndGet(value.coerceAtLeast(0), ::maxOf)
    }

    fun measureUnderruns(
        previous: Int,
        read: () -> Int,
    ): Int =
        try {
            val current = read()
            count(AudioReceiveCounter.OUTPUT_UNDERRUNS, (current - previous).coerceAtLeast(0).toLong())
            current
        } catch (_: RuntimeException) {
            // A diagnostics read must never prevent AudioTrack retirement.
            count(AudioReceiveCounter.DIAGNOSTIC_READ_FAILURES)
            previous
        }

    fun counters(): Map<AudioReceiveCounter, Long> = AudioReceiveCounter.entries.associateWith { counts[it.ordinal].get() }

    fun takePeaks(): Map<AudioReceivePeak, Long> = AudioReceivePeak.entries.associateWith { peaks[it.ordinal].getAndSet(0) }

    fun observeRoute(kind: AudioRouteKind?) {
        if (kind == actualRoute) return
        actualRoute = kind
        count(AudioReceiveCounter.ACTUAL_ROUTE_OBSERVATIONS)
        observer?.onEvent(AudioDiagnosticEventKind.ACTUAL_ROUTE, kind?.ordinal?.toLong() ?: -1)
    }

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
        when (counter) {
            maxInputAgeNanos -> peak(AudioReceivePeak.INPUT_AGE_NS, value)
            maxTalkerAgeNanos -> peak(AudioReceivePeak.TALKER_AGE_NS, value)
            maxCombinedWaitNanos -> peak(AudioReceivePeak.COMBINED_WAIT_NS, value)
        }
    }
}
