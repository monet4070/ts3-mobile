package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A bounded recovery budget, independent of Android and of individual retry attempts. */
internal class SessionPowerPolicy(private val recoveryBudgetMs: Long = 120_000L) {
    private var recoveryStartedAtMs: Long? = null

    fun beginSession() {
        recoveryStartedAtMs = null
    }

    fun leaseMs(
        enabled: Boolean,
        phase: ConnectionPhase,
        networkAvailable: Boolean,
        nowMs: Long,
    ): Long {
        if (phase == ConnectionPhase.CONNECTED || phase in disconnectedPhases) {
            recoveryStartedAtMs = null
        } else if (phase in recoveryPhases && recoveryStartedAtMs == null) {
            recoveryStartedAtMs = nowMs
        }
        // ERROR releases the lock but retains the budget across failed retries.
        if (!enabled || !networkAvailable) return 0L
        return when (phase) {
            ConnectionPhase.CONNECTED -> MAX_LEASE_MS
            in recoveryPhases ->
                (recoveryBudgetMs - (nowMs - (recoveryStartedAtMs ?: nowMs)))
                    .coerceIn(0L, MAX_LEASE_MS)
            else -> 0L
        }
    }

    companion object {
        const val MAX_LEASE_MS = 600_000L
        private val recoveryPhases = setOf(ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING)
        private val disconnectedPhases = setOf(ConnectionPhase.DISCONNECTING, ConnectionPhase.DISCONNECTED)
    }
}

internal interface SessionCpuLock {
    val isHeld: Boolean

    fun acquire(timeoutMs: Long)

    fun release()
}

/** Only the service owns this controller; a timed lease is renewed while a live session needs it. */
internal class SessionPowerController(
    private val scope: CoroutineScope,
    private val cpuLock: SessionCpuLock,
    private val clockMs: () -> Long,
    private val onHeldChanged: (Boolean) -> Unit,
    private val policy: SessionPowerPolicy = SessionPowerPolicy(),
    private val isSessionActive: () -> Boolean = { true },
) : AutoCloseable {
    private var enabled = false
    private var phase = ConnectionPhase.DISCONNECTED
    private var networkAvailable = false
    private var renewalJob: Job? = null
    private var closed = false
    private var lastReportedHeld = false

    @Synchronized
    fun startManualSession(
        enabled: Boolean,
        networkAvailable: Boolean,
    ) {
        if (closed) return
        policy.beginSession()
        // Start the lease before the collector observes CONNECTING; use current
        // inputs because explicit disconnect clears the controller's cache.
        update(enabled, ConnectionPhase.CONNECTING, networkAvailable)
    }

    @Synchronized
    fun update(
        enabled: Boolean,
        phase: ConnectionPhase,
        networkAvailable: Boolean,
    ) {
        if (closed) return
        this.enabled = enabled
        this.phase = phase
        this.networkAvailable = networkAvailable
        renewalJob?.cancel()
        val lease = renew()
        if (lease <= 0L) return
        renewalJob =
            scope.launch {
                var nextLease = lease
                while (nextLease > 0L) {
                    delay(minOf(nextLease, RENEW_INTERVAL_MS))
                    nextLease = synchronized(this@SessionPowerController) { if (closed) 0L else renew() }
                }
            }
    }

    private fun renew(): Long {
        val lease = policy.leaseMs(enabled, phase, networkAvailable && isSessionActive(), clockMs())
        val heldBefore = cpuLock.isHeld
        if (lease > 0L) {
            cpuLock.acquire(lease)
        } else if (heldBefore) {
            releaseIfHeld()
        }
        reportHeldState()
        return lease
    }

    @Synchronized
    override fun close() {
        closed = true
        renewalJob?.cancel()
        releaseIfHeld()
        reportHeldState()
    }

    private fun releaseIfHeld() {
        if (!cpuLock.isHeld) return
        try {
            cpuLock.release()
        } catch (error: RuntimeException) {
            // A timed Android lock may expire between isHeld and release.
            // Only tolerate that race; a still-held lock is a real failure.
            if (cpuLock.isHeld) throw error
        }
    }

    private fun reportHeldState() {
        val held = cpuLock.isHeld
        if (held != lastReportedHeld) {
            lastReportedHeld = held
            onHeldChanged(held)
        }
    }

    companion object {
        private const val RENEW_INTERVAL_MS = 300_000L
    }
}
