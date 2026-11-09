package io.github.ts3mobile.audio.opus

import android.content.Context
import android.media.AudioDeviceInfo
import io.github.ts3mobile.protocol.sanitizedFailureTypes

class AudioDeviceRouter internal constructor(
    private val platform: AudioRoutePlatform,
    private val onRoutingChanged: (AudioRoutingState) -> Unit,
) : AutoCloseable {
    constructor(context: Context, onRoutingChanged: (AudioRoutingState) -> Unit) :
        this(AndroidAudioRoutePlatform(context), onRoutingChanged)

    private val lifecycleLock = Any()
    private var started = false
    private var knownBluetoothIds: Set<Int>? = null
    private var bluetoothSuspended = false

    @Volatile private var playbackActive = false

    @Volatile
    var state: AudioRoutingState = AudioRoutingState.Default
        private set

    fun start() =
        synchronized(lifecycleLock) {
            if (started) return
            started = true
            try {
                platform.start(::refreshDevices, ::onBluetoothRouteLost, ::onBluetoothRouteSuspended)
                refreshDevices()
            } catch (error: RuntimeException) {
                started = false
                try {
                    platform.stop()
                } catch (cleanup: RuntimeException) {
                    error.addSuppressed(cleanup)
                }
                knownBluetoothIds = null
                bluetoothSuspended = false
                playbackActive = false
                state = AudioRoutingState.Default
                throw error
            }
        }

    fun selectRoute(routeId: Int) =
        synchronized(lifecycleLock) {
            if (!started) return
            refreshDevices()
            val routes = state.routes
            if (routes.none { it.id == routeId }) {
                publish(state.copy(routes = routes, error = AudioRoutingError(AudioRoutingErrorKind.DEVICE_UNAVAILABLE)))
                return
            }
            switchRoute(routeId, routes, error = null)
        }

    fun preferredOutputDevice(): AudioDeviceInfo? = synchronized(lifecycleLock) { platform.preferredOutput(state.selectedRouteId) }

    fun preferredInputDevice(): AudioDeviceInfo? = synchronized(lifecycleLock) { platform.preferredInput(state.selectedRouteId) }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    /** Called only after playback has acquired focus, so another call keeps its route. */
    fun prepareForPlayback() =
        synchronized(lifecycleLock) {
            if (!started || !bluetoothSuspended || state.safetyMuted) return
            val routes = platform.routes()
            if (routes.none { it.id == state.selectedRouteId && it.kind == AudioRouteKind.BLUETOOTH }) {
                onBluetoothRouteLost()
                return
            }
            switchRoute(state.selectedRouteId, routes, error = null)
        }

    private fun onBluetoothRouteSuspended() =
        synchronized(lifecycleLock) {
            if (!started || state.safetyMuted || bluetoothSuspended || state.selectedRoute.kind != AudioRouteKind.BLUETOOTH) return
            val stillAvailable = platform.routes().any { it.id == state.selectedRouteId && it.kind == AudioRouteKind.BLUETOOTH }
            if (playbackActive || !stillAvailable) {
                onBluetoothRouteLost()
                return
            }
            bluetoothSuspended = true
            // Retire old PCM and arm a fresh Bluetooth-only attempt, without opening SCO while idle.
            publish(state.copy(switching = true))
            publish(state.copy(switching = false))
        }

    /** Also called by playback when its actual output disagrees with Bluetooth protection. */
    fun onBluetoothRouteLost() =
        synchronized(lifecycleLock) {
            if (!started) return
            bluetoothSuspended = false
            publish(state.copy(safetyMuted = true, error = AudioRoutingError(AudioRoutingErrorKind.BLUETOOTH_ROUTE_LOST)))
        }

    /** Only a deliberate listening action clears a safety mute; reconnecting never does. */
    fun acknowledgeSafetyMute() =
        synchronized(lifecycleLock) {
            if (!state.safetyMuted) return
            publish(state.copy(safetyMuted = false, error = null))
            if (state.selectedRoute.kind == AudioRouteKind.BLUETOOTH) switchRoute(state.selectedRouteId, platform.routes(), error = null)
        }

    override fun close() = stop()

    fun stop() =
        synchronized(lifecycleLock) {
            if (!started) return
            started = false
            platform.stop()
            knownBluetoothIds = null
            bluetoothSuspended = false
            playbackActive = false
            state = AudioRoutingState.Default
        }

    private fun refreshDevices() =
        synchronized(lifecycleLock) {
            if (!started) return
            val routes = platform.routes()
            val bluetooth = routes.filter { it.kind == AudioRouteKind.BLUETOOTH }
            val previousBluetooth = knownBluetoothIds
            knownBluetoothIds = bluetooth.map { it.id }.toSet()
            val removed = state.selectedRouteId != resolveSelectedRouteId(state.selectedRouteId, routes)
            val newBluetooth = bluetooth.filter { previousBluetooth == null || it.id !in previousBluetooth }
            val selectedBluetooth = bluetooth.firstOrNull { it.id == state.selectedRouteId }
            val automaticBluetooth =
                if (previousBluetooth == null || newBluetooth.isNotEmpty() || removed) {
                    selectedBluetooth ?: newBluetooth.firstOrNull() ?: bluetooth.firstOrNull()
                } else {
                    null
                }
            val target = automaticBluetooth?.id ?: resolveSelectedRouteId(state.selectedRouteId, routes)
            val error = if (removed) AudioRoutingError(AudioRoutingErrorKind.DEVICE_DISCONNECTED) else state.error
            if (removed) {
                // Mute before requesting fallback; Android itself may already have rerouted.
                publish(state.copy(routes = routes, safetyMuted = true, error = error))
            }
            if (target != state.selectedRouteId || previousBluetooth == null) {
                switchRoute(target, routes, error)
            } else {
                publish(state.copy(routes = routes, error = error))
            }
        }

    private fun switchRoute(
        routeId: Int,
        routes: List<AudioRouteOption>,
        error: AudioRoutingError?,
    ) {
        bluetoothSuspended = false
        publish(state.copy(routes = routes, selectedRouteId = routeId, switching = true, error = error))
        val failure =
            try {
                if (platform.select(routeId)) null else AudioRoutingError(AudioRoutingErrorKind.SWITCH_DENIED)
            } catch (cause: RuntimeException) {
                AudioRoutingError(AudioRoutingErrorKind.SWITCH_FAILED, cause.sanitizedFailureTypes())
            }
        publish(state.copy(switching = false, safetyMuted = state.safetyMuted || failure != null, error = failure ?: state.error))
    }

    private fun publish(updated: AudioRoutingState) {
        state = updated
        onRoutingChanged(updated)
    }

    companion object {
        internal fun routeKindForDeviceType(type: Int): AudioRouteKind = AudioRouteDevices.routeKindForDeviceType(type)

        fun routeOrder(kind: AudioRouteKind): Int = AudioRouteDevices.routeOrder(kind)

        fun inputTypesFor(outputType: Int): List<Int> = AudioRouteDevices.inputTypesFor(outputType)
    }
}
