package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInMicrophonePositionTest {
    @Test fun recognizesExplicitPositionsWithoutGuessingFromVendorAddresses() {
        assertEquals(BuiltInMicrophonePosition.BOTTOM, AudioRouteDevices.builtInMicrophonePosition("bottom"))
        assertEquals(BuiltInMicrophonePosition.BACK, AudioRouteDevices.builtInMicrophonePosition(" BACK "))
        assertEquals(BuiltInMicrophonePosition.FRONT, AudioRouteDevices.builtInMicrophonePosition("front"))
        assertEquals(BuiltInMicrophonePosition.TOP, AudioRouteDevices.builtInMicrophonePosition("top"))
        assertNull(AudioRouteDevices.builtInMicrophonePosition(""))
        assertNull(AudioRouteDevices.builtInMicrophonePosition("vendor-mic-bottom-2"))
    }
}
