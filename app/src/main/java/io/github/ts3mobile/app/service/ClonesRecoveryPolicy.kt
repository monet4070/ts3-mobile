package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionStatus

/**
 * Parameters of the bounded first-connect recovery that follows a server clone
 * limit refusal.
 *
 * These are product parameters, not a promise about the server: the client cannot
 * observe when the server releases a session it still holds, so the window is a
 * fixed budget after which the user is asked to act instead.
 */
internal class ClonesRecoveryPolicy(
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    val totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS,
) {
    init {
        require(backoffMs.isNotEmpty())
        require(backoffMs.all { it >= 0L })
        require(totalBudgetMs > 0L)
    }

    /** 1, 2, 4 and 8 seconds, then 8 seconds for every later attempt. */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt > 0) { "Attempt numbers start at one" }
        return backoffMs[(attempt - 1).coerceAtMost(backoffMs.lastIndex)]
    }

    /**
     * True while a failure inside an already open window may be retried: the clone
     * refusal itself, or a transient network failure the existing classification
     * already marked retryable. Every other failure (password, ban, kick, protocol
     * or configuration error) ends the window at once.
     */
    fun canContinueWithinWindow(status: ConnectionStatus): Boolean =
        status.category == ConnectionFailureCategory.CLONES_LIMIT || status.retryable

    private companion object {
        val DEFAULT_BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L)
        const val DEFAULT_TOTAL_BUDGET_MS = 60_000L
    }
}
