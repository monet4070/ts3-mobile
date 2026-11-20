package io.github.ts3mobile.audio.opus

import java.util.concurrent.atomic.AtomicLong

/** Short producer/worker handoff; never hold this monitor while invoking audio I/O. */
internal class PlaybackInvalidation {
    data class Command(val revision: Long, val global: Boolean, val clients: Set<Int>, val profile: ReceiveDelayProfile?)

    private val revision = AtomicLong()
    private var global = false
    private var pendingProfile: ReceiveDelayProfile? = null
    private val clients = mutableSetOf<Int>()

    fun permitsWrite(appliedRevision: Long): Boolean = revision.get() == appliedRevision

    @Synchronized fun invalidateAll(
        profile: ReceiveDelayProfile? = null,
        clearInput: () -> Unit = {},
    ) {
        revision.incrementAndGet()
        clearInput()
        global = true
        if (profile != null) pendingProfile = profile
        clients.clear()
    }

    @Synchronized fun invalidateParticipants(affected: Set<Int>) {
        if (affected.isEmpty()) return
        if (!global) clients.addAll(affected)
        revision.incrementAndGet()
    }

    @Synchronized fun take(): Command? {
        if (!global && clients.isEmpty()) return null
        val command = Command(revision.get(), global, clients.toSet(), pendingProfile)
        global = false
        pendingProfile = null
        clients.clear()
        return command
    }
}
