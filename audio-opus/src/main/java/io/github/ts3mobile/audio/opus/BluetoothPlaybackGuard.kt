package io.github.ts3mobile.audio.opus

internal enum class PlaybackRouteDecision { ALLOW, WAIT_FOR_BLUETOOTH, MUTE }

/** Shared across talkspurts, so recreating an AudioTrack cannot bypass protection. */
internal class BluetoothPlaybackGuard {
    private var routeId = SYSTEM_AUDIO_ROUTE_ID
    private var required = false
    private var bluetoothSeen = false
    private var generation = 0L

    @Synchronized
    fun revision(): Long = generation

    @Synchronized
    fun acknowledgeSafetyMute() {
        if (!required) bluetoothSeen = false
    }

    /** A new, explicitly requested Bluetooth attempt must not inherit the retired track's route. */
    @Synchronized
    fun beginBluetoothAttempt() {
        if (!required) return
        bluetoothSeen = false
        generation++
    }

    @Synchronized
    fun select(
        routeId: Int,
        requireBluetooth: Boolean,
    ): Boolean {
        if (this.routeId == routeId && required == requireBluetooth) return false
        this.routeId = routeId
        required = requireBluetooth
        bluetoothSeen = false
        generation++
        return true
    }

    @Synchronized
    fun evaluate(
        actual: AudioRouteKind?,
        revision: Long = generation,
    ): PlaybackRouteDecision {
        if (revision != generation) return PlaybackRouteDecision.WAIT_FOR_BLUETOOTH
        if (actual == AudioRouteKind.BLUETOOTH) {
            if (required || routeId == SYSTEM_AUDIO_ROUTE_ID) bluetoothSeen = true
            return PlaybackRouteDecision.ALLOW
        }
        if (actual == null && (required || bluetoothSeen)) return PlaybackRouteDecision.WAIT_FOR_BLUETOOTH
        if (bluetoothSeen) return PlaybackRouteDecision.MUTE
        return if (required) PlaybackRouteDecision.WAIT_FOR_BLUETOOTH else PlaybackRouteDecision.ALLOW
    }
}
