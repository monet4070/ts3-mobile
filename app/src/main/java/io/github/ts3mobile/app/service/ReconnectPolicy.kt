package io.github.ts3mobile.app.service

import kotlin.math.roundToLong

internal class ReconnectPolicy(
    private val delaysMs: List<Long> = DEFAULT_DELAYS_MS,
    private val jitterRatio: Double = 0.0,
    private val randomUnit: () -> Double = Math::random,
) {
    init {
        require(delaysMs.isNotEmpty())
        require(delaysMs.all { it >= 0L })
        require(jitterRatio in 0.0..0.5)
    }

    fun delayForAttempt(attempt: Int): Long {
        require(attempt > 0)
        val base = delaysMs[(attempt - 1).coerceAtMost(delaysMs.lastIndex)]
        if (jitterRatio == 0.0 || base == 0L) return base
        val unit = randomUnit().coerceIn(0.0, 1.0)
        val factor = 1.0 + (2.0 * unit - 1.0) * jitterRatio
        return (base * factor).roundToLong().coerceAtLeast(0L)
    }

    private companion object {
        val DEFAULT_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
    }
}
