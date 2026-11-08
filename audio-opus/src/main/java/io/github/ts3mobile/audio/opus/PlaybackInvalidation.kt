package io.github.ts3mobile.audio.opus

import java.util.concurrent.atomic.AtomicLong

/** Short producer/worker handoff; never hold this monitor while invoking audio I/O. */
internal class PlaybackInvalidation {
    data class Command(val revision: Long, val global: Boolean, val clients: Set<Int>, val focusInterrupted: Boolean)

    private val revision = AtomicLong()
    private var global = false
    private var focusInterrupted = false
    private val clients = mutableSetOf<Int>()

    fun permitsWrite(appliedRevision: Long): Boolean = revision.get() == appliedRevision

    @Synchronized fun invalidateAll(interrupted: Boolean = false) {
        global = true
        focusInterrupted = focusInterrupted || interrupted
        clients.clear()
        revision.incrementAndGet()
    }

    @Synchronized fun invalidateParticipants(affected: Set<Int>) {
        if (affected.isEmpty()) return
        if (!global) clients.addAll(affected)
        revision.incrementAndGet()
    }

    @Synchronized fun take(): Command? {
        if (!global && clients.isEmpty()) return null
        val command = Command(revision.get(), global, clients.toSet(), focusInterrupted)
        global = false
        clients.clear()
        focusInterrupted = false
        return command
    }
}
