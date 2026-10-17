package io.github.ts3mobile.app.service

/**
 * Owns the lifecycle token used to reject callbacks from replaced sessions.
 * Every begin/invalidate operation advances the generation monotonically.
 */
internal class SessionGeneration {
    private val lock = Any()
    private var current = 0L
    private var disconnectRequested = true

    fun beginSession(): Long =
        synchronized(lock) {
            disconnectRequested = false
            ++current
        }

    fun invalidate() =
        synchronized(lock) {
            disconnectRequested = true
            current++
        }

    fun isActive(generation: Long): Boolean =
        synchronized(lock) {
            generation == current && !disconnectRequested
        }

    /** Runs a short state mutation atomically with session registration. */
    fun withActive(
        generation: Long,
        action: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (generation != current || disconnectRequested) return@synchronized false
            action()
            true
        }

    val isDisconnectRequested: Boolean
        get() = synchronized(lock) { disconnectRequested }
}
