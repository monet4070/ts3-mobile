package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionFailureCategory
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Privacy-safe journal result codes; see ADR-0009. */
internal enum class ClonesRecoveryResult(val code: Long) {
    CONNECTED(0L),
    CANCELLED(1L),
    EXPIRED(2L),
    TERMINAL(3L),
    FAILED(4L),
}

/** One immutable deadline covers the refused initial handshake and every retry. */
internal class ClonesRecoveryEngine(
    private val host: ConnectionCoordinatorHost,
    private val delegate: Delegate,
    private val policy: ClonesRecoveryPolicy = ClonesRecoveryPolicy(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    internal interface Delegate {
        val desiredConfig: ServerConfig?

        suspend fun performAttemptWithin(
            epoch: Long,
            remainingMs: Long,
        ): AttemptResult

        suspend fun finishTerminalFailure(
            status: ConnectionStatus,
            epoch: Long,
        )

        suspend fun suspendAudioForRecovery()

        /** Detach/close this epoch's attempt before canceling its blocking coroutine. */
        fun abortRecoveryAttempt(epoch: Long) = Unit

        /** Complete shared audio cleanup under the attempt mutex, guarded by epoch. */
        suspend fun finishRecoveryExpiry(epoch: Long) = Unit
    }

    private class Window(val epoch: Long, val deadlineMs: Long) {
        var result: ClonesRecoveryResult? = null
        var runner: Job? = null
        var watchdog: Job? = null
        var attempt = 0
    }

    private val lock = Any()
    private var window: Window? = null
    val isActive: Boolean get() = synchronized(lock) { window?.result == null && window != null }

    /** Registration starts the watchdog immediately, even if initial connect has not returned. */
    fun noteRejection(epoch: Long) {
        synchronized(lock) {
            if (!host.sessionGeneration.isActive(epoch) || window?.epoch == epoch) return
            val next = Window(epoch, clockMs() + policy.totalBudgetMs)
            window = next
            host.recordDiagnostic(DiagnosticEventKind.CLONES_RECOVERY_STARTED)
            next.watchdog =
                host.serviceScope.launch(start = CoroutineStart.LAZY) {
                    while (isPending(next)) {
                        val remaining = next.deadlineMs - clockMs()
                        if (remaining <= 0) {
                            expire(next)
                            return@launch
                        }
                        delay(remaining)
                    }
                }
            next.watchdog?.start()
        }
    }

    fun isRejectedFor(epoch: Long): Boolean = synchronized(lock) { window?.let { it.epoch == epoch && it.result == null } == true }

    fun isExpiredFor(epoch: Long): Boolean =
        synchronized(lock) {
            window?.let { it.epoch == epoch && it.result == ClonesRecoveryResult.EXPIRED } == true
        }

    /** Deadline checks also run at the callback seam, before a scheduled watchdog can execute. */
    fun allowsCallbacks(epoch: Long): Boolean =
        synchronized(lock) {
            val current = window?.takeIf { it.epoch == epoch } ?: return@synchronized true
            current.result == ClonesRecoveryResult.CONNECTED || (current.result == null && clockMs() < current.deadlineMs)
        }

    /** Atomically arbitrate connection success against expiry. */
    fun acceptConnected(epoch: Long): Boolean =
        synchronized(lock) {
            if (!host.sessionGeneration.isActive(epoch)) return@synchronized false
            val current = window?.takeIf { it.epoch == epoch } ?: return@synchronized true
            if (current.result == ClonesRecoveryResult.CONNECTED) return@synchronized true
            if (current.result != null || clockMs() >= current.deadlineMs) return@synchronized false
            finish(current, ClonesRecoveryResult.CONNECTED)
        }

    fun cancel() {
        val current = synchronized(lock) { window } ?: return
        finish(current, ClonesRecoveryResult.CANCELLED)
        current.runner?.cancel()
    }

    /** A terminal refusal observed while connect is still blocking must end it promptly. */
    fun noteTerminalFailure(
        epoch: Long,
        status: ConnectionStatus,
    ): Boolean {
        val current =
            synchronized(lock) {
                window?.takeIf { it.epoch == epoch && it.result == null && host.sessionGeneration.isActive(epoch) }
            } ?: return false
        if (policy.canContinueWithinWindow(status)) return false
        if (!finish(current, ClonesRecoveryResult.TERMINAL)) return false
        host.serviceScope.launch {
            delegate.abortRecoveryAttempt(epoch)
            current.runner?.cancel()
            delegate.finishTerminalFailure(status, epoch)
        }
        return true
    }

    fun launch(
        epoch: Long,
        status: ConnectionStatus,
    ) {
        synchronized(lock) {
            val current = window?.takeIf { it.epoch == epoch && it.result == null } ?: return
            if (current.runner != null) return
            current.runner = host.serviceScope.launch(start = CoroutineStart.LAZY) { runWindow(current, status) }
            current.runner?.start()
        }
    }

    private fun isPending(current: Window): Boolean = synchronized(lock) { current.result == null }

    /** Exactly one end record, including windows canceled before the retry job starts. */
    private fun finish(
        current: Window,
        result: ClonesRecoveryResult,
        cancelWatchdog: Boolean = true,
    ): Boolean =
        synchronized(lock) {
            if (current.result != null) return@synchronized false
            current.result = result
            if (cancelWatchdog) current.watchdog?.cancel()
            host.recordDiagnostic(DiagnosticEventKind.CLONES_RECOVERY_ENDED, result.code)
            true
        }

    private suspend fun expire(current: Window) {
        if (!finish(current, ClonesRecoveryResult.EXPIRED, cancelWatchdog = false)) return
        // This watchdog is independent of the attempt coroutine: close happens
        // before cancellation waits for a non-cooperative blocking call to return.
        delegate.abortRecoveryAttempt(current.epoch)
        current.runner?.cancel()
        withContext(NonCancellable) {
            delegate.finishRecoveryExpiry(current.epoch)
            val updated =
                host.sessionGeneration.withActive(current.epoch) {
                    host.mutableState.update { state ->
                        state.withStatus(
                            ConnectionStatus(ConnectionPhase.ERROR, category = ConnectionFailureCategory.CLONES_LIMIT),
                            UserMessage.ClonesLimitExpired,
                        ).copy(snapshot = SessionSnapshot.Empty, isTransmitting = false)
                    }
                    host.refreshDiagnostics()
                }
            if (updated) host.removeForegroundNotificationOnMainThread(current.epoch)
        }
    }

    private suspend fun runWindow(
        current: Window,
        initial: ConnectionStatus,
    ) {
        try {
            if (!policy.canContinueWithinWindow(initial)) {
                if (finish(current, ClonesRecoveryResult.TERMINAL)) delegate.finishTerminalFailure(initial, current.epoch)
                return
            }
            updateState(current, UserMessage.ClonesLimitRetrying)
            delegate.suspendAudioForRecovery()
            while (isPending(current) && host.sessionGeneration.isActive(current.epoch)) {
                val remaining = current.deadlineMs - clockMs()
                if (remaining <= 0) {
                    expire(current)
                    return
                }
                if (!host.networkAvailable.value) {
                    updateState(current, UserMessage.ClonesLimitWaitingForNetwork)
                    host.networkAvailable.first { it }
                    continue
                }
                val attempt = ++current.attempt
                val backoff = minOf(policy.delayForAttempt(attempt), remaining)
                updateState(current, UserMessage.ClonesLimitRetryScheduled(backoff / 1_000, attempt))
                delay(backoff)
                if (!isPending(current)) return
                if (clockMs() >= current.deadlineMs) {
                    expire(current)
                    return
                }
                if (!host.networkAvailable.value) continue
                updateState(current, UserMessage.ClonesLimitRetrying)
                when (val outcome = delegate.performAttemptWithin(current.epoch, current.deadlineMs - clockMs())) {
                    AttemptResult.Expired -> {
                        expire(current)
                        return
                    }
                    AttemptResult.Success -> {
                        if (clockMs() >= current.deadlineMs && isPending(current)) {
                            expire(current)
                        } else {
                            finish(current, ClonesRecoveryResult.CONNECTED)
                        }
                        return
                    }
                    AttemptResult.Stale -> return
                    is AttemptResult.Failed -> {
                        if (!policy.canContinueWithinWindow(outcome.status)) {
                            if (finish(
                                    current,
                                    ClonesRecoveryResult.TERMINAL,
                                )
                            ) {
                                delegate.finishTerminalFailure(outcome.status, current.epoch)
                            }
                            return
                        }
                        delegate.suspendAudioForRecovery()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (finish(current, ClonesRecoveryResult.FAILED)) {
                delegate.finishTerminalFailure(ConnectionStatus(ConnectionPhase.ERROR), current.epoch)
            }
        } finally {
            finish(current, ClonesRecoveryResult.CANCELLED)
        }
    }

    private fun updateState(
        current: Window,
        message: UserMessage,
    ) {
        synchronized(lock) {
            if (current.result != null || clockMs() >= current.deadlineMs) return
            host.sessionGeneration.withActive(current.epoch) {
                host.mutableState.update { state ->
                    state.withStatus(
                        ConnectionStatus(ConnectionPhase.RECONNECTING, retryable = true, category = ConnectionFailureCategory.CLONES_LIMIT),
                        message,
                    ).copy(snapshot = SessionSnapshot.Empty, isTransmitting = false)
                }
                host.updateNotification()
            }
        }
    }
}
