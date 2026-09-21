package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRoutingTest {
    private val routes =
        listOf(
            AudioRoutingState.SystemRoute,
            AudioRouteOption(7, AudioRouteKind.SPEAKER),
            AudioRouteOption(12, AudioRouteKind.BLUETOOTH, deviceName = "Studio Buds"),
        )

    @Test
    fun keepsAnAvailableSelection() {
        assertEquals(12, resolveSelectedRouteId(12, routes))
    }

    @Test
    fun fallsBackToSystemWhenADeviceDisappears() {
        assertEquals(SYSTEM_AUDIO_ROUTE_ID, resolveSelectedRouteId(99, routes))
    }

    @Test
    fun classifiesBluetoothAndWiredDeviceFamiliesForHotPlugRoutes() {
        assertEquals(
            AudioRouteKind.BLUETOOTH,
            AudioDeviceRouter.routeKindForDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
        assertEquals(
            AudioRouteKind.BLUETOOTH,
            AudioDeviceRouter.routeKindForDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        assertEquals(
            AudioRouteKind.WIRED,
            AudioDeviceRouter.routeKindForDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(
            AudioRouteKind.USB,
            AudioDeviceRouter.routeKindForDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET),
        )
        assertEquals(
            AudioRouteKind.OTHER,
            AudioDeviceRouter.routeKindForDeviceType(Int.MIN_VALUE),
        )
    }
}
