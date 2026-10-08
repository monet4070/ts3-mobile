package io.github.ts3mobile.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the identity-aware state of the device's default network.
 *
 * Every callback reads only the payload it was handed — the [Network] identity
 * and the [NetworkCapabilities] snapshot — and never synchronously re-queries
 * [ConnectivityManager] to assemble state. That keeps the callback cheap and,
 * more importantly, prevents a race where a fresh query for a replacement
 * network is published as if it belonged to the callback that just arrived.
 *
 * All state is published as an immutable [DefaultNetworkState]. A stale `onLost`
 * from a replaced network and a duplicate capability callback both collapse to
 * "no change", so they cannot clear the current network or force a reconnect.
 *
 * Lifecycle: construct once, call [start] to register the default-network
 * callback and [close] to unregister it. [state] is safe to read before [start]
 * (it reports [DefaultNetworkState.Unknown]).
 */
internal class DefaultNetworkMonitor(
    context: Context,
    private val onStateChanged: ((DefaultNetworkState) -> Unit)? = null,
) : AutoCloseable {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = DefaultNetworkStateTracker()
    private val lock = Any()

    private val mutableState = MutableStateFlow(DefaultNetworkState.Unknown)

    /** Identity-aware default-network state; collect or read [value] directly. */
    val state: StateFlow<DefaultNetworkState> = mutableState.asStateFlow()

    /** Convenience for callers that only need the boolean gate. */
    val isAvailable: Boolean
        get() = state.value.isAvailable

    private var currentNetwork: Network? = null
    private var currentKey: String? = null
    private var nextKey = 0L

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish {
                    if (network != currentNetwork) {
                        currentNetwork = network
                        currentKey = "network-${++nextKey}"
                    }
                    tracker.onAvailable(checkNotNull(currentKey))
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                publish {
                    val key = keyFor(network) ?: return@publish tracker.current
                    tracker.onCapabilitiesChanged(
                        key,
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    )
                }
            }

            override fun onBlockedStatusChanged(
                network: Network,
                blocked: Boolean,
            ) {
                publish {
                    val key = keyFor(network) ?: return@publish tracker.current
                    tracker.onBlockedStatusChanged(key, blocked)
                }
            }

            override fun onLost(network: Network) {
                publish {
                    val key = keyFor(network) ?: return@publish tracker.current
                    currentNetwork = null
                    currentKey = null
                    tracker.onLost(key)
                }
            }

            override fun onUnavailable() {
                publish {
                    currentNetwork = null
                    currentKey = null
                    tracker.onUnavailable()
                }
            }
        }

    fun start() {
        publish { seedFromCurrentNetwork() }
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    override fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun seedFromCurrentNetwork(): DefaultNetworkState {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            currentNetwork = null
            currentKey = null
            return tracker.onUnavailable()
        }
        currentNetwork = network
        currentKey = "network-${++nextKey}"
        val key = checkNotNull(currentKey)
        tracker.onAvailable(key)
        connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
            tracker.onCapabilitiesChanged(
                key,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            )
        }
        return tracker.current
    }

    private fun keyFor(network: Network): String? = if (network == currentNetwork) currentKey else null

    private fun publish(mutation: () -> DefaultNetworkState) {
        val (status, changed) =
            synchronized(lock) {
                val next = mutation()
                if (mutableState.value == next) {
                    next to false
                } else {
                    mutableState.value = next
                    next to true
                }
            }
        if (changed) onStateChanged?.invoke(status)
    }
}
