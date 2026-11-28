package io.github.ts3mobile.audio.opus

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal interface AudioRoutePlatform {
    fun routes(): List<AudioRouteOption>

    fun inputRoutes(): List<AudioInputOption>

    fun start(
        onDevicesChanged: () -> Unit,
        onBecomingNoisy: () -> Unit,
    )

    fun isAvailable(routeId: Int): Boolean

    fun selectBluetoothCall(routeId: Int): Boolean

    fun clearBluetoothCall()

    fun preferredOutput(routeId: Int): AudioDeviceInfo?

    fun preferredInput(routeId: Int): AudioDeviceInfo?

    fun inputDevice(id: Int): AudioDeviceInfo?

    fun stop()
}

/** Android calls stay here; selection and privacy decisions belong to the router. */
internal class AndroidAudioRoutePlatform(context: Context) : AudioRoutePlatform {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private val bluetoothCall = BluetoothCallRoute(manager)
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothMonitor = BluetoothRouteMonitor(this.context, handler)
    private var registered = false
    private var onDevicesChanged: () -> Unit = {}
    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
        }

    override fun start(
        onDevicesChanged: () -> Unit,
        onBecomingNoisy: () -> Unit,
    ) {
        this.onDevicesChanged = onDevicesChanged
        manager.registerAudioDeviceCallback(callback, handler)
        registered = true
        bluetoothMonitor.start(onBecomingNoisy)
    }

    override fun routes(): List<AudioRouteOption> =
        buildList {
            add(AudioRoutingState.SystemRoute)
            val media =
                outputs().map { device ->
                    val kind = AudioRouteDevices.routeKindForDeviceType(device.type)
                    val name =
                        if (kind != AudioRouteKind.EARPIECE && kind != AudioRouteKind.SPEAKER) {
                            device.displayName()
                        } else {
                            null
                        }
                    AudioRouteOption(device.id, kind, name, AudioRouteDevices.isLeOutput(device.type))
                }
            val calls =
                bluetoothCall.outputs().map { device ->
                    AudioRouteOption(device.id, AudioRouteKind.BLUETOOTH, device.displayName(), bluetoothCall = true)
                }
            (media + calls).distinctBy(AudioRouteOption::id)
                .sortedWith(compareBy({ AudioRouteDevices.routeOrder(it.kind) }, { it.deviceName.orEmpty() }))
                .forEach(::add)
        }

    override fun inputRoutes(): List<AudioInputOption> =
        listOf(AudioInputRouting.Automatic) +
            inputs().mapNotNull { device ->
                val kind = AudioRouteDevices.inputKind(device.type) ?: return@mapNotNull null
                val builtIn = kind == AudioInputKind.BUILTIN
                AudioInputOption(
                    device.id,
                    kind,
                    device.displayName().takeUnless { builtIn },
                    if (builtIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        AudioRouteDevices.builtInMicrophonePosition(device.address)
                    } else {
                        null
                    },
                )
            }.distinctBy(AudioInputOption::id).sortedWith(compareBy({ it.kind }, { it.deviceName.orEmpty() }, { it.id }))

    override fun inputDevice(id: Int): AudioDeviceInfo? =
        inputs().firstOrNull { it.id == id && AudioRouteDevices.inputKind(it.type) != null }

    override fun isAvailable(routeId: Int): Boolean =
        routeId == SYSTEM_AUDIO_ROUTE_ID || preferredOutput(routeId) != null || bluetoothCall.outputs().any { it.id == routeId }

    override fun selectBluetoothCall(routeId: Int): Boolean = bluetoothCall.select(routeId)

    override fun clearBluetoothCall() = bluetoothCall.clear()

    override fun preferredOutput(routeId: Int): AudioDeviceInfo? = outputs().firstOrNull { it.id == routeId }

    override fun preferredInput(routeId: Int): AudioDeviceInfo? {
        val output = preferredOutput(routeId) ?: return null
        val inputs = inputs()
        if (AudioRouteDevices.routeKindForDeviceType(output.type) == AudioRouteKind.BLUETOOTH) {
            return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }
        val sameAddress =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                inputs.firstOrNull { it.type == output.type && output.address.isNotBlank() && it.address == output.address }
            } else {
                null
            }
        return sameAddress ?: AudioRouteDevices.inputTypesFor(output.type).firstNotNullOfOrNull { type ->
            inputs.firstOrNull { it.type == type }
        }
    }

    override fun stop() {
        try {
            clearBluetoothCall()
        } finally {
            try {
                bluetoothMonitor.stop()
            } finally {
                onDevicesChanged = {}
                if (registered) manager.unregisterAudioDeviceCallback(callback)
                registered = false
            }
        }
    }

    private fun outputs(): List<AudioDeviceInfo> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { AudioRouteDevices.isSelectableMediaOutput(it.type) }

    private fun inputs(): Array<AudioDeviceInfo> = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)

    private fun AudioDeviceInfo.displayName(): String? = productName?.toString()?.trim()?.takeIf(String::isNotEmpty)
}
