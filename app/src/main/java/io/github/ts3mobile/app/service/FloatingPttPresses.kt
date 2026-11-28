package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase

internal data class FloatingPttTarget(val connection: String, val channel: Int)

internal data class FloatingPttCapture(val token: Long, val mode: MicrophoneMode)

internal fun TeamSpeakServiceState.floatingPttTarget(): FloatingPttTarget? {
    if (status.phase != ConnectionPhase.CONNECTED || switchingChannelId != null) return null
    return FloatingPttTarget(bookmarkConnection?.token ?: return null, snapshot.currentChannelId ?: return null)
}

internal class FloatingPttPresses(
    private val clockMs: () -> Long,
    private val currentTarget: () -> FloatingPttTarget?,
) {
    private data class Lease(
        val target: FloatingPttTarget,
        val deadline: Long,
        val mode: MicrophoneMode,
        val capture: FloatingPttCapture? = null,
    )

    @Volatile private var lease: Lease? = null
    private var nextPress = 0L

    val isArmed: Boolean get() = lease != null
    val capture: FloatingPttCapture? get() = lease?.takeIf(::valid)?.capture
    val mode: MicrophoneMode? get() = lease?.takeIf(::valid)?.mode
    val isValid: Boolean get() = lease?.let(::valid) == true

    @Synchronized
    fun arm(mode: MicrophoneMode = MicrophoneMode.PUSH_TO_TALK): Boolean {
        if (isArmed) return false
        val initialMode = if (mode.requiresContinuousCapture) MicrophoneMode.PUSH_TO_TALK else mode
        lease = Lease(currentTarget() ?: return false, clockMs() + IDLE_MILLIS, initialMode)
        return true
    }

    @Synchronized
    fun select(
        mode: MicrophoneMode,
        stop: () -> Unit,
    ): Boolean {
        val current = lease?.takeIf(::valid) ?: return false
        if (current.mode == mode) return true
        val capture = if (mode.requiresContinuousCapture) FloatingPttCapture(++nextPress, mode) else null
        val duration = if (capture != null) CONTINUOUS_MILLIS else IDLE_MILLIS
        lease = current.copy(mode = mode, deadline = clockMs() + duration, capture = capture)
        if (current.capture != null) stop()
        return true
    }

    @Synchronized
    fun begin(): Long? {
        val ready = lease ?: return null
        if (ready.capture != null || !valid(ready)) return null
        if (ready.mode != MicrophoneMode.PUSH_TO_TALK) return null
        val token = ++nextPress
        lease = ready.copy(deadline = clockMs() + PRESS_MILLIS, capture = FloatingPttCapture(token, ready.mode))
        return token
    }

    @Synchronized
    fun release(
        token: Long,
        stop: () -> Unit,
    ) {
        val active = lease ?: return
        if (active.mode != MicrophoneMode.PUSH_TO_TALK || active.capture?.token != token) return
        val valid = valid(active)
        lease = if (valid) active.copy(deadline = clockMs() + IDLE_MILLIS, capture = null) else null
        stop()
    }

    @Synchronized
    fun disarm(): Boolean {
        val active = lease ?: return false
        lease = null
        return active.capture != null
    }

    private fun valid(current: Lease): Boolean = clockMs() < current.deadline && current.target == currentTarget()

    private companion object {
        const val IDLE_MILLIS = 300_000L
        const val PRESS_MILLIS = 60_000L
        const val CONTINUOUS_MILLIS = 300_000L
    }
}
