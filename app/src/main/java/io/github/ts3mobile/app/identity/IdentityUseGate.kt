package io.github.ts3mobile.app.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class IdentityUseState { IDLE, CONNECTION, MAINTENANCE }

/** Application-owned qualification shared across Service and Activity lifetimes. */
internal class IdentityUseGate {
    private val lock = Any()
    private var next = 0L
    private val connections = mutableSetOf<Long>()
    private var maintenance: Long? = null
    private val mutableState = MutableStateFlow(IdentityUseState.IDLE)
    val state = mutableState.asStateFlow()

    /** Every superseded owner must also finish; a late old task still blocks maintenance. */
    fun beginConnection(): Long? =
        synchronized(lock) {
            if (maintenance != null) return@synchronized null
            (++next).also {
                connections += it
                publish()
            }
        }

    fun ownsConnection(token: Long): Boolean = synchronized(lock) { token in connections }

    fun finishConnection(token: Long) {
        synchronized(lock) {
            connections -= token
            publish()
        }
    }

    fun beginMaintenance(): Long? =
        synchronized(lock) {
            if (connections.isNotEmpty() || maintenance != null) return@synchronized null
            (++next).also {
                maintenance = it
                publish()
            }
        }

    fun finishMaintenance(token: Long) {
        synchronized(lock) {
            if (maintenance == token) maintenance = null
            publish()
        }
    }

    fun ownsMaintenance(token: Long): Boolean = synchronized(lock) { maintenance == token }

    private fun publish() {
        mutableState.value =
            when {
                maintenance != null -> IdentityUseState.MAINTENANCE
                connections.isNotEmpty() -> IdentityUseState.CONNECTION
                else -> IdentityUseState.IDLE
            }
    }
}
