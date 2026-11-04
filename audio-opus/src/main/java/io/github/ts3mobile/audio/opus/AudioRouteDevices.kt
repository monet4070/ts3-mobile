package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo

internal object AudioRouteDevices {
    fun routeKindForDeviceType(type: Int): AudioRouteKind =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            -> AudioRouteKind.WIRED

            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> AudioRouteKind.BLUETOOTH

            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            -> AudioRouteKind.USB

            else -> AudioRouteKind.OTHER
        }

    fun routeOrder(kind: AudioRouteKind): Int =
        when (kind) {
            AudioRouteKind.SYSTEM -> 0
            AudioRouteKind.EARPIECE -> 1
            AudioRouteKind.SPEAKER -> 2
            AudioRouteKind.WIRED -> 3
            AudioRouteKind.BLUETOOTH -> 4
            AudioRouteKind.USB -> 5
            AudioRouteKind.OTHER -> 6
        }

    fun inputTypesFor(outputType: Int): List<Int> =
        when (outputType) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                listOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                )

            AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                listOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                )

            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            ->
                listOf(
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                )

            else -> listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        }
}
