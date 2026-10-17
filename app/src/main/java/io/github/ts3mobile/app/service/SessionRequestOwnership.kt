package io.github.ts3mobile.app.service

/**
 * Why the service is leaving its session. A closed category, never free text, so
 * it can be journalled without leaking a server address, nickname or identity.
 */
internal enum class ExitTrigger(val reasonMessage: String) {
    /** The user removed the task from the recents list. */
    TASK_REMOVED("Task removed"),

    /** The user pressed disconnect in the app or the notification. */
    USER_DISCONNECT("Disconnected by user"),
}

/**
 * Serializes "which request owns the service right now" between connect and exit
 * requests.
 *
 * Registration is a short synchronous boundary, so it can run on the main thread
 * before any suspension point. Every exit request carries a token that a connect
 * request invalidates: an exit may only clear shared state, remove the
 * notification and stop the service while its own token is still the newest exit
 * token. A boolean "an exit is latest" would not be enough, because an old exit
 * whose teardown is still queued would find it true again after a later exit.
 *
 * A second exit request while an exit already owns the service reuses the same
 * token, so the exit that is already running keeps the right to finish the
 * teardown; a connect clears that ownership, so the next exit starts a fresh
 * token.
 */
internal class SessionRequestOwnership {
    private val lock = Any()
    private var revision = 0L
    private var latestIsExit = false

    /** Registers a connect request, which takes ownership away from any exit. */
    fun beginConnect(): Long = invalidate()

    /** Revokes any queued exit callback, including during service destruction. */
    fun invalidate(): Long =
        synchronized(lock) {
            latestIsExit = false
            ++revision
        }

    /** Registers an exit request and returns the token that owns the teardown. */
    fun beginExit(): Long =
        synchronized(lock) {
            if (!latestIsExit) {
                latestIsExit = true
                ++revision
            }
            revision
        }

    /**
     * True while [token] is still the newest exit token. The service reads this
     * again on the main thread before it stops itself, so a connect the main
     * thread already processed always wins.
     */
    fun isLatestExit(token: Long): Boolean = synchronized(lock) { latestIsExit && revision == token }

    /**
     * Runs [action] only while [token] is still the newest exit token, inside the
     * same lock that registers requests, so a connect cannot register between the
     * check and the action. Keep the action short: request registration waits on
     * this lock, and a connect registers on the main thread.
     */
    fun <T> withLatestExit(
        token: Long,
        action: () -> T,
    ): T? =
        synchronized(lock) {
            if (latestIsExit && revision == token) action() else null
        }

    /** Monotonic registration counter, observable for diagnostics and tests. */
    val currentRevision: Long
        get() = synchronized(lock) { revision }
}
