package io.github.ts3mobile.audio.opus

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal interface AudioRoutePlatform {
    fun routes(): List<AudioRouteOption>

    fun start(
        onDevicesChanged: () -> Unit,
        onBluetoothLost: () -> Unit,
    )

    fun select(routeId: Int): Boolean

    fun preferredOutput(routeId: Int): AudioDeviceInfo?

    fun preferredInput(routeId: Int): AudioDeviceInfo?

    fun stop()
}

/** Android calls stay here; selection and privacy decisions belong to the router. */
internal class AndroidAudioRoutePlatform(context: Context) : AudioRoutePlatform {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothMonitor = BluetoothRouteMonitor(this.context, manager, handler)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn = false
    private var previousBluetoothScoOn = false
    private var onDevicesChanged: () -> Unit = {}
    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
        }

    @Suppress("DEPRECATION")
    override fun start(
        onDevicesChanged: () -> Unit,
        onBluetoothLost: () -> Unit,
    ) {
        this.onDevicesChanged = onDevicesChanged
        previousMode = manager.mode
        previousSpeakerphoneOn = manager.isSpeakerphoneOn
        previousBluetoothScoOn = manager.isBluetoothScoOn
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(callback, handler)
        bluetoothMonitor.start(onBluetoothLost)
    }

    override fun routes(): List<AudioRouteOption> =
        buildList {
            add(AudioRoutingState.SystemRoute)
            outputs().mapNotNull { device ->
                val kind = AudioRouteDevices.routeKindForDeviceType(device.type)
                if (kind == AudioRouteKind.OTHER) return@mapNotNull null
                val name =
                    if (kind in setOf(AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED, AudioRouteKind.USB)) {
                        device.productName?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    } else {
                        null
                    }
                AudioRouteOption(device.id, kind, name)
            }.distinctBy(AudioRouteOption::id)
                .sortedWith(compareBy({ AudioRouteDevices.routeOrder(it.kind) }, { it.deviceName.orEmpty() }))
                .forEach(::add)
        }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun select(routeId: Int): Boolean {
        val output = outputs().firstOrNull { it.id == routeId }
        if (routeId != SYSTEM_AUDIO_ROUTE_ID && output == null) return false
        bluetoothMonitor.expectBluetooth(output?.let { AudioRouteDevices.routeKindForDeviceType(it.type) } == AudioRouteKind.BLUETOOTH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (output == null) manager.clearCommunicationDevice() else return manager.setCommunicationDevice(output)
        } else {
            when (output?.let { AudioRouteDevices.routeKindForDeviceType(it.type) }) {
                AudioRouteKind.BLUETOOTH -> {
                    manager.isSpeakerphoneOn = false
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                }
                AudioRouteKind.SPEAKER -> {
                    manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = false
                    manager.isSpeakerphoneOn = true
                }
                else -> {
                    manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = false
                    manager.isSpeakerphoneOn = false
                }
            }
        }
        return true
    }

    override fun preferredOutput(routeId: Int): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) null else outputs().firstOrNull { it.id == routeId }

    override fun preferredInput(routeId: Int): AudioDeviceInfo? {
        val output = preferredOutput(routeId) ?: return null
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val sameAddress =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                inputs.firstOrNull { it.type == output.type && output.address.isNotBlank() && it.address == output.address }
            } else {
                null
            }
        return sameAddress ?: AudioRouteDevices.inputTypesFor(output.type).firstNotNullOfOrNull {
                type ->
            inputs.firstOrNull { it.type == type }
        }
    }

    @Suppress("DEPRECATION")
    override fun stop() {
        bluetoothMonitor.stop()
        manager.unregisterAudioDeviceCallback(callback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
        } else {
            if (previousBluetoothScoOn) manager.startBluetoothSco() else manager.stopBluetoothSco()
            manager.isBluetoothScoOn = previousBluetoothScoOn
            manager.isSpeakerphoneOn = previousSpeakerphoneOn
        }
        manager.mode = previousMode
    }

    private fun outputs(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.availableCommunicationDevices
        } else {
            manager.getDevices(
                AudioManager.GET_DEVICES_OUTPUTS,
            ).toList()
        }
}
