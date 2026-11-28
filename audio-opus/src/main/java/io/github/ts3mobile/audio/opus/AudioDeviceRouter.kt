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
    private var monitoring = false
    private var keepMonitoring = false
    private var pendingOutput: Int? = null
    private var knownBluetoothIds: Set<Int>? = null

    @Volatile private var playbackActive = false

    @Volatile
    var state: AudioRoutingState = AudioRoutingState.Default
        private set

    fun watchDevices() =
        synchronized(lifecycleLock) {
            ensureMonitoring()
            keepMonitoring = true
            refreshDevices()
        }

    fun start() =
        synchronized(lifecycleLock) {
            if (started) return
            started = true
            try {
                ensureMonitoring()
                refreshDevices()
            } catch (error: RuntimeException) {
                started = false
                try {
                    if (keepMonitoring) platform.clearBluetoothCall() else stopMonitoring()
                } catch (cleanup: RuntimeException) {
                    error.addSuppressed(cleanup)
                }
                knownBluetoothIds = null
                playbackActive = false
                state = AudioRoutingState.Default
                throw error
            }
        }

    fun selectRoute(routeId: Int) =
        synchronized(lifecycleLock) {
            if (!monitoring) return
            refreshDevices()
            val routes = state.routes
            if (routes.none { it.id == routeId }) {
                publish(state.copy(routes = routes, error = AudioRoutingError(AudioRoutingErrorKind.DEVICE_UNAVAILABLE)))
                return
            }
            if (started) {
                switchRoute(routeId, state, error = null)
            } else {
                pendingOutput = routeId
                publish(state.copy(selectedRouteId = routeId, error = null))
            }
        }

    fun selectInput(routeId: Int) =
        synchronized(lifecycleLock) {
            if (!monitoring) return
            refreshDevices()
            if (state.selectedRoute.bluetoothCall) return
            val input = state.input
            publish(
                state.copy(
                    input =
                        if (input.routes.any { it.id == routeId }) {
                            input.copy(selectedId = routeId, error = null)
                        } else {
                            input.copy(error = AudioRoutingErrorKind.DEVICE_UNAVAILABLE)
                        },
                ),
            )
        }

    fun preferredOutputDevice(): AudioDeviceInfo? = synchronized(lifecycleLock) { platform.preferredOutput(state.selectedRouteId) }

    fun isBluetoothCallActive(): Boolean = synchronized(lifecycleLock) { started && state.selectedRoute.bluetoothCall }

    fun preferredInputDevice(): AudioDeviceInfo? =
        synchronized(lifecycleLock) {
            if (started && state.selectedRoute.bluetoothCall) return null
            val selected = state.input.selectedId
            val automatic = if (started || pendingOutput != null) state.selectedRouteId else SYSTEM_AUDIO_ROUTE_ID
            val explicit = if (selected != SYSTEM_AUDIO_ROUTE_ID) platform.inputDevice(selected) else null
            explicit ?: platform.preferredInput(automatic)
        }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun prepareForPlayback() =
        synchronized(lifecycleLock) {
            if (started && !state.safetyMuted && !platform.isAvailable(state.selectedRouteId)) refreshDevices()
        }

    private fun onBecomingNoisy() =
        synchronized(lifecycleLock) {
            if (started && playbackActive && !state.safetyMuted && state.selectedRoute.kind == AudioRouteKind.BLUETOOTH) {
                onBluetoothRouteLost()
            }
        }

    /** Also called by playback when its actual output disagrees with Bluetooth protection. */
    fun onBluetoothRouteLost() =
        synchronized(lifecycleLock) {
            if (!started) return
            val error = AudioRoutingError(AudioRoutingErrorKind.BLUETOOTH_ROUTE_LOST)
            val muted = state.copy(safetyMuted = true, error = error)
            publish(muted)
            if (muted.selectedRoute.bluetoothCall) {
                val routes = platform.routes()
                val media = routes.firstOrNull { it.kind == AudioRouteKind.BLUETOOTH && !it.bluetoothCall }
                switchRoute(media?.id ?: SYSTEM_AUDIO_ROUTE_ID, muted.copy(routes = routes), error)
            }
        }

    /** Only a deliberate listening action clears a safety mute; reconnecting never does. */
    fun acknowledgeSafetyMute() =
        synchronized(lifecycleLock) {
            if (!state.safetyMuted) return
            publish(state.copy(safetyMuted = false, error = null))
            if (state.selectedRoute.kind == AudioRouteKind.BLUETOOTH) {
                switchRoute(state.selectedRouteId, state.copy(routes = platform.routes()), error = null)
            }
        }

    override fun close() =
        synchronized(lifecycleLock) {
            keepMonitoring = false
            started = false
            pendingOutput = null
            knownBluetoothIds = null
            playbackActive = false
            state = AudioRoutingState.Default
            stopMonitoring()
        }

    fun stop() =
        synchronized(lifecycleLock) {
            if (!started) return
            started = false
            knownBluetoothIds = null
            playbackActive = false
            if (keepMonitoring) {
                try {
                    platform.clearBluetoothCall()
                } finally {
                    refreshDevices()
                }
            } else {
                state = AudioRoutingState.Default
                stopMonitoring()
            }
        }

    private fun ensureMonitoring() {
        if (monitoring) return
        try {
            platform.start(::refreshDevices, ::onBecomingNoisy)
            monitoring = true
        } catch (error: RuntimeException) {
            try {
                platform.stop()
            } catch (cleanup: RuntimeException) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    private fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        platform.stop()
    }

    private fun refreshDevices() =
        synchronized(lifecycleLock) {
            if (!monitoring) return
            val routes = platform.routes()
            val inputs = platform.inputRoutes()
            val input = state.input
            val inputRemoved = inputs.none { it.id == input.selectedId }
            val updatedInput =
                if (inputRemoved) {
                    AudioInputRouting(inputs, error = AudioRoutingErrorKind.DEVICE_DISCONNECTED)
                } else {
                    input.copy(routes = inputs)
                }
            val stagedOutput = pendingOutput?.takeIf { requested -> routes.any { it.id == requested } }
            if (!started) {
                pendingOutput = stagedOutput
                publish(AudioRoutingState(routes, stagedOutput ?: SYSTEM_AUDIO_ROUTE_ID, input = updatedInput))
                return
            }
            pendingOutput = null
            val bluetooth = routes.filter { it.kind == AudioRouteKind.BLUETOOTH && !it.bluetoothCall }
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
            val selectedCall = routes.firstOrNull { it.id == state.selectedRouteId && it.bluetoothCall }
            val target = stagedOutput ?: selectedCall?.id ?: automaticBluetooth?.id ?: resolveSelectedRouteId(state.selectedRouteId, routes)
            val error = if (removed) AudioRoutingError(AudioRoutingErrorKind.DEVICE_DISCONNECTED) else state.error
            val updated = state.copy(routes = routes, input = updatedInput, safetyMuted = state.safetyMuted || removed, error = error)
            if (removed) {
                // Mute before requesting fallback; Android itself may already have rerouted.
                publish(updated)
            }
            if (target != state.selectedRouteId || previousBluetooth == null) {
                switchRoute(target, updated, error)
            } else {
                publish(updated)
            }
        }

    private fun switchRoute(
        routeId: Int,
        base: AudioRoutingState,
        error: AudioRoutingError?,
    ) {
        val bluetoothCall = base.routes.any { it.id == routeId && it.bluetoothCall }
        publish(base.copy(selectedRouteId = routeId, switching = true, error = error))
        val failure =
            try {
                val accepted =
                    if (bluetoothCall) {
                        platform.selectBluetoothCall(routeId)
                    } else {
                        platform.clearBluetoothCall()
                        platform.isAvailable(routeId)
                    }
                if (accepted) null else AudioRoutingError(AudioRoutingErrorKind.SWITCH_DENIED)
            } catch (cause: RuntimeException) {
                AudioRoutingError(AudioRoutingErrorKind.SWITCH_FAILED, cause.sanitizedFailureTypes())
            }
        if (bluetoothCall && failure != null) {
            val cleanupFailure =
                try {
                    platform.clearBluetoothCall()
                    null
                } catch (cause: RuntimeException) {
                    AudioRoutingError(AudioRoutingErrorKind.SWITCH_FAILED, cause.sanitizedFailureTypes())
                }
            val fallback = base.selectedRouteId.takeUnless { base.selectedRoute.bluetoothCall } ?: SYSTEM_AUDIO_ROUTE_ID
            publish(state.copy(selectedRouteId = fallback, switching = false, safetyMuted = true, error = cleanupFailure ?: failure))
            return
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
