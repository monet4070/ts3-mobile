package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    val isActive: Boolean
        get() = reconnectJob?.isActive == true

    fun resetAttemptCount() {
        reconnectAttempt = 0
    }

    fun cancel() {
        reconnectJob?.cancel()
    }

    fun launch(
        cause: ConnectionStatus,
        epoch: Long,
    ) {
        if (!host.sessionGeneration.isActive(epoch)) return
        val message =
            if (host.networkAvailable.value) {
                UserMessage.ConnectionInterrupted(cause.detail?.takeIf(String::isNotBlank))
            } else {
                UserMessage.WaitingForNetwork
            }
        host.mutableState.update { current ->
            current
                .withStatus(
                    ConnectionStatus(
                        ConnectionPhase.RECONNECTING,
                        cause.detail,
                        retryable = true,
                    ),
                    message,
                )
                .copy(
                    snapshot = SessionSnapshot.Empty,
                    isTransmitting = false,
                    switchingChannelId = null,
                )
        }
        host.refreshDiagnostics()
        host.updateNotification()
        if (isActive) return

        val job =
            host.serviceScope.launch {
                delegate.suspendAudioForReconnect()
                loop(epoch)
            }
        reconnectJob = job
        job.invokeOnCompletion {
            if (reconnectJob === job) reconnectJob = null
        }
    }

    private suspend fun loop(epoch: Long) {
        val config = delegate.desiredConfig ?: return
        while (host.sessionGeneration.isActive(epoch)) {
            if (!host.networkAvailable.value) {
                host.mutableState.update { current ->
                    current.withStatus(
                        ConnectionStatus(
                            ConnectionPhase.RECONNECTING,
                            retryable = true,
                        ),
                        UserMessage.WaitingForNetwork,
                    )
                }
                host.updateNotification()
                host.networkAvailable.first { it }
            }
            if (!host.sessionGeneration.isActive(epoch)) return

            val attempt = reconnectAttempt + 1
            val delayMs = host.reconnectPolicy.delayForAttempt(attempt)
            host.mutableState.update { current ->
                current.withStatus(
                    ConnectionStatus(
                        ConnectionPhase.RECONNECTING,
                        retryable = true,
                    ),
                    UserMessage.ReconnectScheduled(delayMs / 1_000, attempt),
                )
            }
            host.updateNotification()
            delay(delayMs)
            if (!host.networkAvailable.value) continue
            if (!host.sessionGeneration.isActive(epoch)) return

            reconnectAttempt = attempt
            host.mutableState.update { current ->
                current.withStatus(
                    ConnectionStatus(
                        ConnectionPhase.RECONNECTING,
                        retryable = true,
                    ),
                    UserMessage.Reconnecting(attempt),
                )
            }
            host.updateNotification()

            when (val result = delegate.performAttempt(epoch)) {
                AttemptResult.Success -> return
                AttemptResult.Stale -> return
                is AttemptResult.Failed -> {
                    if (!result.retryable) {
                        delegate.finishTerminalFailure(result.status, epoch)
                        return
                    }
                    delegate.suspendAudioForReconnect()
                }
            }
        }
    }

    internal interface Delegate {
        val desiredConfig: ServerConfig?

        suspend fun performAttempt(epoch: Long): AttemptResult

        suspend fun finishTerminalFailure(
            status: ConnectionStatus,
            epoch: Long,
        )

        suspend fun suspendAudioForReconnect()
    }
}
