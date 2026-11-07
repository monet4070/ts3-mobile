package io.github.ts3mobile.app.identity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tracks work before scheduling it; cancellation alone never releases identity ownership. */
internal class IdentityConnectionLease private constructor(private val gate: IdentityUseGate, private val token: Long) {
    private val lock = Any()
    private val jobs = mutableSetOf<Job>()
    private var retiring = false
    val isActive: Boolean get() = synchronized(lock) { !retiring && gate.ownsConnection(token) }

    fun prepare(
        scope: CoroutineScope,
        block: suspend CoroutineScope.() -> Unit,
    ): Job? {
        val job =
            synchronized(lock) {
                if (retiring) return null
                scope.launch(start = CoroutineStart.LAZY, block = block).also { pending ->
                    jobs += pending
                    pending.invokeOnCompletion {
                        synchronized(lock) {
                            jobs -= pending
                            releaseIfSettled()
                        }
                    }
                }
            }
        return job
    }

    fun retire() {
        synchronized(lock) {
            retiring = true
            releaseIfSettled()
        }
    }

    private fun releaseIfSettled() {
        if (retiring && jobs.isEmpty()) gate.finishConnection(token)
    }

    companion object {
        fun acquire(gate: IdentityUseGate): IdentityConnectionLease? = gate.beginConnection()?.let { IdentityConnectionLease(gate, it) }
    }
}
