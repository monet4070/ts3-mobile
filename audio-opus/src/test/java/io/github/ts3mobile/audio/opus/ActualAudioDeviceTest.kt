package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActualAudioDeviceTest {
    @Test fun mediaCallAndLeAudioAreReportedSeparately() {
        assertEquals(ActualAudioDevice.BLUETOOTH_A2DP, actualAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(ActualAudioDevice.BLUETOOTH_SCO, actualAudioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(ActualAudioDevice.BLUETOOTH_LE, actualAudioDevice(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(ActualAudioDevice.BLUETOOTH_OTHER, actualAudioDevice(AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(ActualAudioDevice.BUILTIN_MIC, actualAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertNull(actualAudioDevice(AudioDeviceInfo.TYPE_UNKNOWN))
        assertNull(actualAudioDevice(null))
    }

    @Test fun outputObserverClearsDeviceAndDoesNotRepeatUnchangedRoutes() {
        val stats = ReceiveStats()
        val readings = mutableListOf<ActualAudioDevice?>()
        stats.setOutputDeviceObserver { readings += it }
        stats.confirmOutputDevice(ActualAudioDevice.BLUETOOTH_A2DP)
        stats.confirmOutputDevice(ActualAudioDevice.BLUETOOTH_A2DP)
        stats.confirmOutputDevice(null)
        stats.setOutputDeviceObserver(null)
        stats.confirmOutputDevice(ActualAudioDevice.SPEAKER)
        assertEquals(listOf(null, ActualAudioDevice.BLUETOOTH_A2DP, null), readings)
    }
}
