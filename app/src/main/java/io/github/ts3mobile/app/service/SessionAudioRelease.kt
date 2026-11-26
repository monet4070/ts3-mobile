package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.sanitizedFailureTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun ConnectionCoordinatorHost.releaseCapture(): UserMessage.MicrophoneFailed? =
    try {
        stopMicrophoneCapture()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        UserMessage.MicrophoneFailed(error.sanitizedFailureTypes())
    }

/**
 * Attempts to acquire [Mutex] within [timeoutMs] without leaking lock ownership if the timeout
 * races with grant or if the outer coroutine is cancelled.
 */
internal suspend fun Mutex.tryLockBounded(timeoutMs: Long): Boolean {
    if (tryLock()) return true
    if (timeoutMs <= 0L) return false
    val acquired = CompletableDeferred<Unit>()
    return try {
        coroutineScope {
            val waiter =
                launch {
                    lock()
                    if (!acquired.complete(Unit)) {
                        unlock()
                    }
                }
            val won = withTimeoutOrNull(timeoutMs) { acquired.await() } != null
            if (won) {
                true
            } else {
                acquired.cancel()
                val wonOnCancel = acquired.isCompleted && !acquired.isCancelled
                waiter.cancel()
                wonOnCancel
            }
        }
    } catch (cancelled: CancellationException) {
        acquired.cancel()
        if (acquired.isCompleted && !acquired.isCancelled) {
            unlock()
        }
        throw cancelled
    }
}
