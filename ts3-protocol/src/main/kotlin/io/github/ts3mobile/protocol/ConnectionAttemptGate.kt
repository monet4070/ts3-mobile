package io.github.ts3mobile.protocol

import java.util.concurrent.atomic.AtomicReference

/** Orders the terminal status of one connection attempt without invoking listeners under a lock. */
internal class ConnectionAttemptGate {
    private val state = AtomicReference<State>(State.Active)

    fun beginConnectedEmission(): Boolean = state.compareAndSet(State.Active, State.EmittingConnected)

    fun completeConnectedEmission(): Throwable? {
        while (true) {
            when (val current = state.get()) {
                State.EmittingConnected -> {
                    if (state.compareAndSet(current, State.Connected)) return null
                }

                is State.FailurePending -> {
                    if (state.compareAndSet(current, State.Failed(current.error))) {
                        return current.error
                    }
                }

                else -> error("No connected status emission is active")
            }
        }
    }

    fun recordFailure(error: Throwable): FailureDelivery {
        while (true) {
            when (val current = state.get()) {
                State.Active -> {
                    if (state.compareAndSet(current, State.Failed(error))) {
                        return FailureDelivery.EMIT_NOW
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
                is State.Failed,
                -> return FailureDelivery.ALREADY_RECORDED
            }
        }
    }

    fun failureOrNull(): Throwable? =
        when (val current = state.get()) {
            is State.FailurePending -> current.error
            is State.Failed -> current.error
            else -> null
        }

    enum class FailureDelivery {
        EMIT_NOW,
        DEFER_UNTIL_CONNECTED_EMITTED,
        ALREADY_RECORDED,
    }

    private sealed interface State {
        data object Active : State

        data object EmittingConnected : State

        data object Connected : State

        data class FailurePending(
            val error: Throwable,
        ) : State

        data class Failed(
            val error: Throwable,
        ) : State
    }
}
