package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the automatic reconnect loop: waits for network availability,
 * applies the bounded backoff policy and retries the connection attempt
 * until it succeeds, the session epoch is invalidated, or the failure is
 * terminal. The coordinator supplies the attempt and terminal-failure
 * behavior through [Delegate] so the loop stays independently testable.
 */
internal class ReconnectEngine(
    private val host: ConnectionCoordinatorHost,
    private val delegate: Delegate,
    private val prepareJob: (suspend CoroutineScope.() -> Unit) -> Job? = { block ->
        host.serviceScope.launch(start = CoroutineStart.LAZY, block = block)
    },
) {
    private class Recovery(val epoch: Long) {
        lateinit var job: Job
        var pendingLoss = false
    }

    private val lock = Any()
    private val reconnectAttempt = AtomicInteger()
    private var recovery: Recovery? = null

    val isActive: Boolean
        get() = synchronized(lock) { recovery != null }

    fun resetAttemptCount() {
        reconnectAttempt.set(0)
    }

    fun cancel() {
        val previous = synchronized(lock) { recovery.also { recovery = null } }
        previous?.job?.cancel()
    }

    fun launch(
        cause: ConnectionStatus,
        epoch: Long,
    ) {
        val job = reserveLoss(cause, epoch) ?: return
        host.refreshDiagnostics()
        host.updateNotification()
        job.start()
    }

    fun reserveLoss(
        cause: ConnectionStatus,
        epoch: Long,
    ): Job? {
        if (!isEpochActive(epoch)) return null
        val message =
            if (host.networkAvailable.value) {
                UserMessage.ConnectionInterrupted(cause.detail?.takeIf(String::isNotBlank))
            } else {
                UserMessage.WaitingForNetwork
            }
        var scheduled: Recovery? = null
        host.sessionGeneration.withActive(epoch) {
            scheduled = synchronized(lock) { registerLoss(epoch) } ?: return@withActive
            host.mutableState.update { current ->
                current
                    .withStatus(ConnectionStatus(ConnectionPhase.RECONNECTING, cause.detail, retryable = true), message)
                    .copy(snapshot = SessionSnapshot.Empty, isTransmitting = false, switchingChannelId = null)
            }
        }
        return scheduled?.job
    }

    private fun registerLoss(epoch: Long): Recovery? {
        val current = recovery
        if (current != null) {
            if (current.epoch != epoch) return null
            current.pendingLoss = true
            return current
        }

        val owner = Recovery(epoch)
        val job =
            prepareJob {
                if (!isEpochActive(epoch)) return@prepareJob
                if (!delegate.suspendAudioForReconnect(epoch)) return@prepareJob
                loop(owner)
            } ?: return null
        owner.job = job
        recovery = owner
        job.invokeOnCompletion {
            synchronized(lock) { if (recovery === owner) recovery = null }
        }
        return owner.takeUnless { job.isCancelled }
    }

    private suspend fun loop(owner: Recovery) {
        val epoch = owner.epoch
        while (isEpochActive(epoch)) {
            if (!host.networkAvailable.value) {
                if (!publish(epoch, UserMessage.WaitingForNetwork)) return
                host.networkAvailable.first { it }
            }
            if (!isEpochActive(epoch)) return

            val attempt = reconnectAttempt.get() + 1
            val delayMs = host.reconnectPolicy.delayForAttempt(attempt)
            if (!publish(epoch, UserMessage.ReconnectScheduled(delayMs / 1_000, attempt))) return
            delay(delayMs)
            if (!host.networkAvailable.value) continue
            if (!startAttempt(owner)) return

            reconnectAttempt.set(attempt)
            if (!publish(epoch, UserMessage.Reconnecting(attempt))) return

            when (val result = delegate.performAttempt(epoch)) {
                AttemptResult.Success -> {
                    if (!continueAfterSuccess(owner) || !delegate.suspendAudioForReconnect(epoch)) return
                }
                AttemptResult.Stale -> return
                is AttemptResult.Failed -> {
                    if (!result.retryable) {
                        delegate.finishTerminalFailure(result.status, epoch)
                        return
                    }
                    if (!delegate.suspendAudioForReconnect(epoch)) return
                }
            }
        }
    }

    private fun startAttempt(owner: Recovery): Boolean =
        synchronized(lock) {
            if (recovery !== owner) return@synchronized false
            owner.pendingLoss = false
            true
        }

    private fun continueAfterSuccess(owner: Recovery): Boolean =
        synchronized(lock) {
            if (recovery !== owner) return@synchronized false
            if (owner.pendingLoss) {
                owner.pendingLoss = false
                true
            } else {
                recovery = null
                false
            }
        }

    private fun publish(
        epoch: Long,
        message: UserMessage,
    ): Boolean {
        val updated =
            host.sessionGeneration.withActive(epoch) {
                host.mutableState.update { current ->
                    current.withStatus(ConnectionStatus(ConnectionPhase.RECONNECTING, retryable = true), message)
                }
            }
        if (updated) host.updateNotification()
        return updated
    }

    private fun isEpochActive(epoch: Long): Boolean = host.sessionGeneration.isActive(epoch) && delegate.isEpochActive(epoch)

    internal interface Delegate {
        fun isEpochActive(epoch: Long): Boolean = true

        suspend fun performAttempt(epoch: Long): AttemptResult

        suspend fun finishTerminalFailure(
            status: ConnectionStatus,
            epoch: Long,
        )

        suspend fun suspendAudioForReconnect(epoch: Long): Boolean
    }
}
