package io.github.ts3mobile.app.probe

import android.util.Log
import kotlin.math.ceil

internal class ProbeJournal {
    var presses = 0
        private set
    var starts = 0
        private set
    var usable = 0
        private set
    var failures = 0
        private set
    private val openings = ArrayDeque<Long>()
    private val releases = ArrayDeque<Long>()

    fun begin() {
        presses++
    }

    fun complete(
        outcome: ProbeCaptureOutcome,
        downMs: Long,
        upMs: Long?,
    ) {
        outcome.startedMs?.let {
            starts++
            add(openings, it - downMs)
        }
        if (outcome.usableInput) usable++
        if (outcome.failed) failures++
        if (upMs != null) add(releases, (outcome.finishedMs - upMs).coerceAtLeast(0L))
        event("capture_finished starts=$starts usable=$usable failures=$failures startMs=${startTiming()} releaseMs=${releaseTiming()}")
    }

    fun startTiming(): String = timing(openings)

    fun releaseTiming(): String = timing(releases)

    private fun add(
        samples: ArrayDeque<Long>,
        measurement: Long,
    ) {
        if (samples.size == 64) samples.removeFirst()
        samples.addLast(measurement)
    }

    private fun timing(samples: ArrayDeque<Long>): String {
        if (samples.isEmpty()) return "—"
        val ordered = samples.sorted()
        return "${ordered[ceil(ordered.size * .50).toInt() - 1]}/${ordered[ceil(ordered.size * .95).toInt() - 1]}"
    }

    companion object {
        fun event(message: String) {
            Log.i("Ts3PttProbe", message)
        }
    }
}
