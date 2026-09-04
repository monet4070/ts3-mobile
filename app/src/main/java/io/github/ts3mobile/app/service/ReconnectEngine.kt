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
        val detail =
            if (host.networkAvailable.value) {
                "连接中断，准备自动重连：${cause.detail.orEmpty()}".trimEnd('：')
            } else {
                WAITING_FOR_NETWORK_DETAIL
            }
        host.mutableState.update { current ->
            current.copy(
                status =
                    ConnectionStatus(
                        ConnectionPhase.RECONNECTING,
                        detail,
                        retryable = true,
                    ),
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
                    current.copy(
                        status =
                            ConnectionStatus(
                                ConnectionPhase.RECONNECTING,
                                WAITING_FOR_NETWORK_DETAIL,
                                retryable = true,
                            ),
                    )
                }
                host.updateNotification()
                host.networkAvailable.first { it }
            }
            if (!host.sessionGeneration.isActive(epoch)) return

            val attempt = reconnectAttempt + 1
            val delayMs = host.reconnectPolicy.delayForAttempt(attempt)
            host.mutableState.update { current ->
                current.copy(
                    status =
                        ConnectionStatus(
                            ConnectionPhase.RECONNECTING,
                            "${delayMs / 1_000} 秒后进行第 $attempt 次重连",
                            retryable = true,
                        ),
                )
            }
            host.updateNotification()
            delay(delayMs)
            if (!host.networkAvailable.value) continue
            if (!host.sessionGeneration.isActive(epoch)) return

            reconnectAttempt = attempt
            host.mutableState.update { current ->
                current.copy(
                    status =
                        ConnectionStatus(
                            ConnectionPhase.RECONNECTING,
                            "正在进行第 $attempt 次重连",
                            retryable = true,
                        ),
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

    internal companion object {
        const val WAITING_FOR_NETWORK_DETAIL = "网络不可用，恢复后自动重连"
    }
}
