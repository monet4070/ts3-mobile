package io.github.ts3mobile.app.identity

/** Process-owned qualification model; C1 tests it before C2 attaches real lifecycle/transactions. */
internal class IdentityUseGate {
    private val lock = Any()
    private var next = 0L
    private var connection: Long? = null
    private var maintenance: Long? = null

    /** Replacement connects may supersede an older connection owner, never maintenance. */
    fun beginConnection(): Long? =
        synchronized(lock) {
            if (maintenance != null) return@synchronized null
            (++next).also { connection = it }
        }

    /** Caller must retain the owner through all handshake/reconnect/exit work, not just phase changes. */
    fun finishConnection(token: Long) {
        synchronized(lock) { if (connection == token) connection = null }
    }

    fun beginMaintenance(): Long? =
        synchronized(lock) {
            if (connection != null || maintenance != null) return@synchronized null
            (++next).also { maintenance = it }
        }

    fun finishMaintenance(token: Long) {
        synchronized(lock) { if (maintenance == token) maintenance = null }
    }

    fun ownsMaintenance(token: Long): Boolean = synchronized(lock) { maintenance == token }
}
