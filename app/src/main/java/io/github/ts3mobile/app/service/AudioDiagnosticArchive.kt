package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioReceiveCounter
import io.github.ts3mobile.audio.opus.AudioReceivePeak

/** Bounded in-memory ownership; callbacks never wait for a file write. */
internal class AudioDiagnosticArchive(initial: AudioDiagnosticArchiveState = AudioDiagnosticArchiveState()) {
    private val sessions = ArrayDeque(initial.sessions)
    private val samples = ArrayDeque(initial.samples)
    private val events = ArrayDeque(initial.events)
    private val pending = ArrayDeque<Pair<Long, String>>()
    private var revision = 0L
    private var rewrite = true
    private var nextSession = (initial.sessions.maxOfOrNull { it.id } ?: 0) + 1
    private var failedWrites = initial.failedWrites
    private var invalidRecords = initial.invalidRecords
    private var persistedMs = initial.persistedMs
    private var trimmedSamples = initial.trimmedSamples

    @Synchronized fun begin(now: Long): Long {
        val id = nextSession++
        val session = AudioDiagnosticSession(id, now)
        sessions.addLast(session)
        changed(AudioDiagnosticFormat.session(session))
        while (sessions.size > MAX_SESSIONS) {
            val old = sessions.removeFirst().id
            samples.removeAll { it.session == old }
            events.removeAll { it.session == old }
        }
        event(AudioDiagnosticEntry(now, id, AudioDiagnosticEvent.STARTED))
        return id
    }

    @Synchronized fun sample(value: AudioDiagnosticSample) {
        if (sessions.none { it.id == value.session }) return
        val copy =
            value.copy(
                snapshot =
                    value.snapshot.copy(
                        counters =
                            AudioReceiveCounter.entries.associateWith {
                                (value.snapshot.counters[it] ?: 0).coerceAtLeast(0)
                            },
                        peaks = AudioReceivePeak.entries.associateWith { (value.snapshot.peaks[it] ?: 0).coerceAtLeast(0) },
                    ),
            )
        samples.addLast(copy)
        while (samples.count { it.session == value.session } > MAX_SAMPLES_PER_SESSION) {
            samples.remove(samples.first { it.session == value.session })
            trimmedSamples++
        }
        changed(AudioDiagnosticFormat.sample(copy))
    }

    @Synchronized fun event(value: AudioDiagnosticEntry) {
        if (sessions.none { it.id == value.session }) return
        events.addLast(value)
        val mark = value.kind == AudioDiagnosticEvent.USER_MARK
        val limit = eventLimit(value.kind)
        while (events.count { it.session == value.session && (it.kind == AudioDiagnosticEvent.USER_MARK) == mark } > limit) {
            events.remove(events.first { it.session == value.session && (it.kind == AudioDiagnosticEvent.USER_MARK) == mark })
        }
        changed(AudioDiagnosticFormat.event(value))
    }

    @Synchronized fun end(
        session: Long,
        now: Long,
        kind: AudioDiagnosticEvent = AudioDiagnosticEvent.STOPPED,
    ) {
        val old = sessions.firstOrNull { it.id == session && it.endedMs == 0L } ?: return
        val updated = old.copy(endedMs = now)
        val index = sessions.indexOf(old)
        sessions[index] = updated
        changed(AudioDiagnosticFormat.session(updated))
        event(AudioDiagnosticEntry(now, session, kind))
    }

    @Synchronized fun recoverInterrupted() {
        sessions.filter { it.endedMs == 0L }.forEach {
            // The exact kill time is unknown: preserve the last sample time, not the reopen time.
            val last = samples.lastOrNull { sample -> sample.session == it.id }?.timeMs ?: it.startedMs
            end(it.id, last, AudioDiagnosticEvent.PREVIOUS_RUN_INTERRUPTED)
        }
    }

    @Synchronized fun state(): AudioDiagnosticArchiveState =
        AudioDiagnosticArchiveState(
            sessions.toList(),
            samples.toList(),
            events.toList(),
            failedWrites,
            invalidRecords,
            persistedMs,
            trimmedSamples,
        )

    @Synchronized fun status(): AudioDiagnosticStatus =
        AudioDiagnosticStatus(
            samples.size,
            sessions.size,
            samples.lastOrNull()?.timeMs ?: 0,
            persistedMs,
            failedWrites,
            invalidRecords,
            events.count { it.kind == AudioDiagnosticEvent.USER_MARK },
        )

    @Synchronized fun checkpoint(): AudioDiagnosticCheckpoint =
        AudioDiagnosticCheckpoint(
            revision,
            pending.map { it.second },
            rewrite,
            state(),
        )

    @Synchronized fun saved(
        checkpoint: AudioDiagnosticCheckpoint,
        timeMs: Long,
    ) {
        while (pending.firstOrNull()?.first?.let { it <= checkpoint.revision } == true) pending.removeFirst()
        persistedMs = timeMs
        if (revision == checkpoint.revision) rewrite = false
    }

    @Synchronized fun failedWrite() {
        failedWrites++
        rewrite = true
    }

    private fun changed(row: String) {
        revision++
        pending.addLast(revision to row)
        if (pending.size > MAX_PENDING) {
            pending.clear()
            rewrite = true
        }
    }

    companion object {
        const val MAX_SESSIONS = 3
        const val MAX_SAMPLES_PER_SESSION = 120
        const val MAX_EVENTS_PER_SESSION = 64
        private const val MAX_MARKS_PER_SESSION = 16
        private const val MAX_PENDING = 1_024

        fun eventLimit(kind: AudioDiagnosticEvent): Int =
            if (kind == AudioDiagnosticEvent.USER_MARK) MAX_MARKS_PER_SESSION else MAX_EVENTS_PER_SESSION - MAX_MARKS_PER_SESSION
    }
}
