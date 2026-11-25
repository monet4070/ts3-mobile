package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo

enum class ActualAudioDevice {
    BUILTIN_MIC,
    EARPIECE,
    SPEAKER,
    WIRED,
    USB,
    BLUETOOTH_A2DP,
    BLUETOOTH_SCO,
    BLUETOOTH_OTHER,
    OTHER,
}

internal fun actualAudioDevice(type: Int?): ActualAudioDevice? =
    when (type) {
        null, AudioDeviceInfo.TYPE_UNKNOWN -> null
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> ActualAudioDevice.BUILTIN_MIC
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ActualAudioDevice.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> ActualAudioDevice.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> ActualAudioDevice.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> ActualAudioDevice.BLUETOOTH_SCO
        else ->
            when (AudioRouteDevices.routeKindForDeviceType(type)) {
                AudioRouteKind.WIRED -> ActualAudioDevice.WIRED
                AudioRouteKind.USB -> ActualAudioDevice.USB
                AudioRouteKind.BLUETOOTH -> ActualAudioDevice.BLUETOOTH_OTHER
                else -> ActualAudioDevice.OTHER
            }
    }
