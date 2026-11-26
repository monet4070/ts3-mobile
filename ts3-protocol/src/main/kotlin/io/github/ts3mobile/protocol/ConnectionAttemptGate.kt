package io.github.ts3mobile.protocol

import java.util.concurrent.atomic.AtomicReference

/** Orders the terminal status of one connection attempt without invoking listeners under a lock. */
internal class ConnectionAttemptGate {
    private val state = AtomicReference<State>(State.Active)

    fun beginConnectedEmission(): Boolean = state.compareAndSet(State.Active, State.EmittingConnected)

    fun completeConnectedEmission(): DeferredTerminalOutcome? {
        while (true) {
            when (val current = state.get()) {
                State.EmittingConnected -> {
                    if (state.compareAndSet(current, State.Connected)) return null
                }

                is State.FailurePending -> {
                    if (state.compareAndSet(current, State.Failed(current.error))) {
                        return DeferredTerminalOutcome.Failure(current.error)
                    }
                }

                is State.DisconnectPending -> {
                    if (state.compareAndSet(current, State.Failed(current.error))) {
                        return DeferredTerminalOutcome.ServerDisconnected(current.status)
                    }
                }

                else -> error("No connected status emission is active")
            }
        }
    }

    fun recordFailure(
        error: Throwable,
        suppressActiveEmission: Boolean = false,
    ): FailureDelivery {
        while (true) {
            when (val current = state.get()) {
                State.Active -> {
                    if (state.compareAndSet(current, State.Failed(error))) {
                        return if (suppressActiveEmission) {
                            FailureDelivery.SUPPRESSED_FOR_RETRY
                        } else {
                            FailureDelivery.EMIT_NOW
                        }
                    }
                }

                State.EmittingConnected -> {
                    if (state.compareAndSet(current, State.FailurePending(error))) {
                        return FailureDelivery.DEFER_UNTIL_CONNECTED_EMITTED
                    }
                }

                State.Connected -> {
                    if (state.compareAndSet(current, State.Failed(error))) {
                        return FailureDelivery.EMIT_NOW
                    }
                }

                is State.FailurePending,
                is State.DisconnectPending,
                is State.Failed,
                -> return FailureDelivery.ALREADY_RECORDED
            }
        }
    }

    fun recordServerDisconnect(
        status: ConnectionStatus,
        error: Throwable,
    ): FailureDelivery {
        while (true) {
            when (val current = state.get()) {
                State.Active,
                State.Connected,
                -> {
                    if (state.compareAndSet(current, State.Failed(error))) {
                        return FailureDelivery.EMIT_NOW
                    }
                }

                State.EmittingConnected -> {
                    if (state.compareAndSet(current, State.DisconnectPending(status, error))) {
                        return FailureDelivery.DEFER_UNTIL_CONNECTED_EMITTED
                    }
                }

                is State.FailurePending,
                is State.DisconnectPending,
                is State.Failed,
                -> return FailureDelivery.ALREADY_RECORDED
            }
        }
    }

    fun failureOrNull(): Throwable? =
        when (val current = state.get()) {
            is State.FailurePending -> current.error
            is State.DisconnectPending -> current.error
            is State.Failed -> current.error
            else -> null
        }

    sealed interface DeferredTerminalOutcome {
        data class Failure(
            val error: Throwable,
        ) : DeferredTerminalOutcome

        data class ServerDisconnected(
            val status: ConnectionStatus,
        ) : DeferredTerminalOutcome
    }

    enum class FailureDelivery {
        EMIT_NOW,
        DEFER_UNTIL_CONNECTED_EMITTED,
        SUPPRESSED_FOR_RETRY,
        ALREADY_RECORDED,
    }

    private sealed interface State {
        data object Active : State

        data object EmittingConnected : State

        data object Connected : State

        data class FailurePending(
            val error: Throwable,
        ) : State

        data class DisconnectPending(
            val status: ConnectionStatus,
            val error: Throwable,
        ) : State

        data class Failed(
            val error: Throwable,
        ) : State
    }
}
