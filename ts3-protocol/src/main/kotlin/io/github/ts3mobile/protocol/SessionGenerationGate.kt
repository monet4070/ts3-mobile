package io.github.ts3mobile.protocol

/**
 * Serializes session replacement with callback state updates.
 * A stale callback cannot enter its mutation block after a new generation starts.
 */
internal class SessionGenerationGate {
    private val lock = Any()
    private var current = 0L

    fun begin(): Long = advance { it }

    fun <T> begin(action: (Long) -> T): T = advance(action)

    fun invalidate(): Long = advance { it }

    fun <T> invalidate(action: (Long) -> T): T = advance(action)

    fun currentToken(): Long = synchronized(lock) { current }

    fun isCurrent(token: Long): Boolean = synchronized(lock) { token == current }

    fun <T> withCurrent(
        token: Long,
        action: () -> T,
    ): T? =
        synchronized(lock) {
            if (token == current) action() else null
        }

    private fun <T> advance(action: (Long) -> T): T =
        synchronized(lock) {
            action(++current)
        }
}
