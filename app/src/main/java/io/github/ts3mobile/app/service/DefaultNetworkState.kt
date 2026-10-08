package io.github.ts3mobile.app.service

/**
 * Identity-aware snapshot of Android's default network.
 *
 * [generation] advances only when the default network identity changes, so a
 * duplicate capability callback for the same network keeps the same value and
 * cannot be mistaken for a network switch.
 *
 * [isPending] is true when an identity has been adopted but its capabilities
 * have not been reported yet, so usability is still unknown. A pending state
 * never reports [isAvailable] and must never be treated as a loss: Android
 * always follows `onAvailable` with `onCapabilitiesChanged`, and judging the
 * gap as "no network" would force a healthy session to reconnect on every
 * switch. [isAvailable] is true only for a confirmed network with internet
 * access that is not blocked.
 *
 * [networkKey] is an opaque identity token. Only equality across successive
 * updates is meaningful; it is never a host name, address or net id.
 */
data class DefaultNetworkState(
    val isAvailable: Boolean = false,
    val isBlocked: Boolean = false,
    val isPending: Boolean = false,
    val networkKey: String? = null,
    val generation: Long = 0L,
) {
    companion object {
        val Unknown = DefaultNetworkState()
    }
}

/**
 * Pure state machine behind [DefaultNetworkMonitor], kept free of Android types
 * so the identity, pending, duplicate-callback and stale-`onLost` rules are
 * unit-tested on the JVM.
 *
 * A key is adopted on `onAvailable`; callbacks that carry a key which is not the
 * current one are ignored, so a late `onLost` from a replaced network cannot
 * clear the replacement. Capability and blocked updates are idempotent: an
 * identical repeat returns an equal state and cannot drive a reconnect.
 */
internal class DefaultNetworkStateTracker {
    private var generation = 0L
    private var currentKey: String? = null
    private var internetCapability: Boolean? = null
    private var blocked = false

    val current: DefaultNetworkState
        get() = snapshot()

    fun onAvailable(key: String): DefaultNetworkState {
        if (key != currentKey) {
            generation++
            currentKey = key
            // Unknown until onCapabilitiesChanged arrives. The state is pending,
            // not usable and not a loss.
            internetCapability = null
            blocked = false
        }
        return snapshot()
    }

    fun onCapabilitiesChanged(
        key: String,
        hasInternet: Boolean,
    ): DefaultNetworkState {
        if (key != currentKey) return snapshot()
        internetCapability = hasInternet
        return snapshot()
    }

    fun onBlockedStatusChanged(
        key: String,
        blocked: Boolean,
    ): DefaultNetworkState {
        if (key != currentKey) return snapshot()
        this.blocked = blocked
        return snapshot()
    }

    fun onLost(key: String): DefaultNetworkState {
        if (key != currentKey) return snapshot()
        clear()
        return snapshot()
    }

    fun onUnavailable(): DefaultNetworkState {
        clear()
        return snapshot()
    }

    private fun clear() {
        currentKey = null
        internetCapability = null
        blocked = false
    }

    private fun snapshot(): DefaultNetworkState {
        val key = currentKey
        val pending = key != null && internetCapability == null
        return DefaultNetworkState(
            isAvailable = key != null && internetCapability == true && !blocked,
            isBlocked = blocked,
            isPending = pending,
            networkKey = key,
            generation = generation,
        )
    }
}
