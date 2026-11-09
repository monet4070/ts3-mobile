package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class MonitoredSession(val epoch: Long, val session: Ts3SessionClient, val listener: Ts3SessionListener)

internal data class NetworkProbeObservation(val binding: MonitoredSession, val networkGeneration: Long, val alive: Boolean)

internal sealed interface ConnectionWatchObservation {
    val binding: MonitoredSession

    data class Tick(override val binding: MonitoredSession) : ConnectionWatchObservation

    data class ExecutionGap(override val binding: MonitoredSession, val seconds: Long) : ConnectionWatchObservation

    data class TransportClosed(override val binding: MonitoredSession) : ConnectionWatchObservation
}

internal enum class NetworkTransition { PENDING, UNAVAILABLE, SWITCHED, UNCHANGED }

/** Observations only. The coordinator owns jobs, identity leases and every recovery decision. */
internal class ConnectionMonitor(private val clockMs: () -> Long) {
    @Volatile var networkState = DefaultNetworkState.Unknown
        private set
    private var lastAvailableGeneration: Long? = null

    @Synchronized fun networkChanged(state: DefaultNetworkState): NetworkTransition {
        networkState = state
        if (state.isPending && !state.isBlocked) return NetworkTransition.PENDING
        if (!state.isAvailable) {
            lastAvailableGeneration = null
            return NetworkTransition.UNAVAILABLE
        }
        val previous = lastAvailableGeneration
        lastAvailableGeneration = state.generation
        return if (previous != null && previous != state.generation) NetworkTransition.SWITCHED else NetworkTransition.UNCHANGED
    }

    fun isCurrentNetwork(generation: Long): Boolean {
        val current = networkState
        return current.generation == generation && !current.isPending && current.isAvailable
    }

    suspend fun probe(
        binding: MonitoredSession,
        generation: Long,
    ): NetworkProbeObservation {
        val alive =
            try {
                // Suspend implementations are bounded here; a blocking adapter retains its own protocol timeout.
                withTimeoutOrNull(ConnectionLivenessPolicy.PROBE_TIMEOUT_MS) {
                    binding.session.verifyLiveness(ConnectionLivenessPolicy.PROBE_TIMEOUT_MS)
                } ?: false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
        return NetworkProbeObservation(binding, generation, alive)
    }

    suspend fun watch(
        binding: MonitoredSession,
        isCurrent: (MonitoredSession) -> Boolean,
        report: (ConnectionWatchObservation) -> Unit,
    ) {
        var previousTickMs = clockMs()
        while (currentCoroutineContext().isActive && isCurrent(binding)) {
            val nowMs = clockMs()
            val gapMs = nowMs - previousTickMs
            previousTickMs = nowMs
            if (gapMs >= WATCH_EXECUTION_GAP_THRESHOLD_MS && isCurrent(binding)) {
                report(ConnectionWatchObservation.ExecutionGap(binding, gapMs / MILLIS_PER_SECOND))
            }
            if (!isCurrent(binding)) return
            val connected = binding.session.transportConnected
            if (!isCurrent(binding)) return
            if (connected == false) {
                report(ConnectionWatchObservation.TransportClosed(binding))
                return
            }
            report(ConnectionWatchObservation.Tick(binding))
            delay(CONNECTED_WATCH_INTERVAL_MS)
        }
    }

    private companion object {
        const val CONNECTED_WATCH_INTERVAL_MS = 1_000L
        const val WATCH_EXECUTION_GAP_THRESHOLD_MS = 10_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
