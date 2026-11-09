package io.github.ts3mobile.audio.opus

/** Fixed numeric categories only: never payloads, client IDs, addresses or device names. */
enum class AudioReceiveCounter {
    SUBMITTED,
    ACCEPTED,
    STALE,
    INVALID,
    BUDGET_RESETS,
    DUPLICATES,
    LATE,
    PLC,
    FEC_ATTEMPTS,
    FEC_FAILURES,
    PCM_DROPPED,
    FOCUS_DENIALS,
    FOCUS_INTERRUPTS,
    OUTPUT_FAILURES,
    OUTPUT_RECOVERIES,
    OUTPUT_EXHAUSTED,
    DRAIN_TIMEOUTS,
    REBUFFERS,
    ARRIVAL_GAPS_OVER_60_MS,
    WORKER_GAPS_OVER_30_MS,
    SEQUENCE_SKIPPED,
    OUTPUTS_CREATED,
    OUTPUT_SAMPLES,
    ZERO_WRITES,
    OUTPUT_UNDERRUNS,
    ACTUAL_ROUTE_OBSERVATIONS,
    INPUT_CLEARED,
    PCM_RESET_DROPPED,
    GLOBAL_INVALIDATIONS,
    PARTICIPANT_INVALIDATIONS,
    ROUTE_CALLBACKS,
    DIAGNOSTIC_READ_FAILURES,
}

enum class AudioReceivePeak {
    ARRIVAL_GAP_NS,
    INPUT_QUEUE_PACKETS,
    INPUT_AGE_NS,
    TALKER_AGE_NS,
    COMBINED_WAIT_NS,
    WORKER_GAP_NS,
    OUTPUT_WRITE_NS,
    OUTPUT_BACKLOG_SAMPLES,
}

enum class AudioDiagnosticEventKind { FOCUS_GAINED, FOCUS_INTERRUPTED, ACTUAL_ROUTE, OUTPUT_CREATED }

/** Counters are cumulative within a playback generation; peaks cover the interval since the last take. */
data class AudioReceiveDiagnostics(
    val generation: Long = 0,
    val active: Boolean = false,
    val counters: Map<AudioReceiveCounter, Long> = emptyMap(),
    val peaks: Map<AudioReceivePeak, Long> = emptyMap(),
    val actualRoute: AudioRouteKind? = null,
)

/** Callbacks must only append to memory/queue work. They can run on the audio worker. */
interface AudioDiagnosticsObserver {
    fun onStarted(snapshot: AudioReceiveDiagnostics) = Unit

    fun onEvent(
        kind: AudioDiagnosticEventKind,
        value: Long = 0,
    )

    fun onStopped(snapshot: AudioReceiveDiagnostics)
}

/** Single sampling owner; ending a worker seals its last sample before another generation can start. */
internal class PlaybackDiagnostics(private val stats: ReceiveStats) {
    private var generation = 0L
    private var baseline = emptyMap<AudioReceiveCounter, Long>()
    private var ended = true
    private var finalSnapshot: AudioReceiveDiagnostics? = null

    fun begin() {
        val snapshot =
            synchronized(this) {
                generation++
                baseline = stats.counters()
                stats.takePeaks()
                ended = false
                finalSnapshot = null
                take(true)
            }
        stats.observer?.onStarted(snapshot)
    }

    @Synchronized fun take(active: Boolean): AudioReceiveDiagnostics =
        finalSnapshot ?: AudioReceiveDiagnostics(
            generation,
            active,
            stats.counters().mapValues { (key, value) -> (value - (baseline[key] ?: 0)).coerceAtLeast(0) },
            stats.takePeaks(),
            stats.actualRoute,
        )

    fun end() {
        val snapshot =
            synchronized(this) {
                if (ended) return
                ended = true
                take(false).also { finalSnapshot = it }
            }
        // Never invoke another owner while holding the sampling lock.
        stats.observer?.onStopped(snapshot)
    }
}
