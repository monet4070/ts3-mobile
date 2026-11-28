package io.github.ts3mobile.app.probe

internal enum class ProbePhase { IDLE, READY, STARTING, SAMPLING, RELEASING }

internal class ProbePressSession(private val now: () -> Long) {
    var phase = ProbePhase.IDLE
        private set
    var generation = 0L
        private set
    var active: Long? = null
        private set
    var ready = false
        private set
    private var deadline = 0L

    fun arm(): Boolean {
        if (active != null || ready) return false
        ready = true
        phase = ProbePhase.READY
        deadline = now() + IDLE_MILLIS
        return true
    }

    fun press(): Long? {
        if (phase != ProbePhase.READY || !ready || expired()) return null
        phase = ProbePhase.STARTING
        deadline = now() + PRESS_MILLIS
        return (++generation).also { active = it }
    }

    fun started(token: Long): Boolean {
        if (active != token || phase != ProbePhase.STARTING || expired()) return false
        phase = ProbePhase.SAMPLING
        return true
    }

    fun release(token: Long): Boolean {
        if (active != token || phase !in setOf(ProbePhase.STARTING, ProbePhase.SAMPLING)) return false
        phase = ProbePhase.RELEASING
        return true
    }

    fun finished(token: Long) {
        if (active != token) return
        active = null
        phase = if (ready) ProbePhase.READY else ProbePhase.IDLE
        if (ready) deadline = now() + IDLE_MILLIS
    }

    fun disarm() {
        ready = false
        phase = if (active == null) ProbePhase.IDLE else ProbePhase.RELEASING
    }

    fun expired(): Boolean = ready && phase != ProbePhase.RELEASING && now() >= deadline

    private companion object {
        const val IDLE_MILLIS = 300_000L
        const val PRESS_MILLIS = 60_000L
    }
}
