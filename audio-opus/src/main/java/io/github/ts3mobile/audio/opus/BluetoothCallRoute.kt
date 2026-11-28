package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

@Suppress("DEPRECATION")
internal class BluetoothCallRoute(private val manager: AudioManager) {
    private var requested = false

    fun outputs(): List<AudioDeviceInfo> {
        val outputs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.availableCommunicationDevices
            } else {
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) outputs else listOfNotNull(outputs.singleOrNull())
    }

    fun select(routeId: Int): Boolean {
        val output = outputs().firstOrNull { it.id == routeId } ?: return false
        requested = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return manager.setCommunicationDevice(output)
        manager.startBluetoothSco()
        manager.isBluetoothScoOn = true
        return true
    }

    fun clear() {
        if (!requested) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                try {
                    manager.isBluetoothScoOn = false
                } finally {
                    manager.stopBluetoothSco()
                }
            }
        } finally {
            manager.mode = AudioManager.MODE_NORMAL
        }
        requested = false
    }
}
