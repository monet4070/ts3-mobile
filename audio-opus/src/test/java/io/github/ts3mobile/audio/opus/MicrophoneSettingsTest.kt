package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MicrophoneSettingsTest {
    @Test fun defaultsPreserveExistingProcessingAndUnityGain() {
        assertEquals(MicrophoneSettings(100, true, true, -40), MicrophoneSettings())
    }

    @Test fun invalidGainAndThresholdFailAtBoundary() {
        for (volume in listOf(-1, 201)) assertThrows(IllegalArgumentException::class.java) { MicrophoneSettings(volumePercent = volume) }
        for (threshold in listOf(
            -61,
            -14,
        )) assertThrows(IllegalArgumentException::class.java) { MicrophoneSettings(gateThresholdDb = threshold) }
        MicrophoneSettings(0, gateThresholdDb = -60)
        MicrophoneSettings(200, gateThresholdDb = -15)
    }
}
