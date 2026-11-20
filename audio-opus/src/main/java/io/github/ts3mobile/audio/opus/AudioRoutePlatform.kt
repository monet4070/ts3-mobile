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

    fun start(
        onDevicesChanged: () -> Unit,
        onBecomingNoisy: () -> Unit,
    )

    fun isAvailable(routeId: Int): Boolean

    fun preferredOutput(routeId: Int): AudioDeviceInfo?

    fun preferredInput(routeId: Int): AudioDeviceInfo?

    fun stop()
}

/** Android calls stay here; selection and privacy decisions belong to the router. */
internal class AndroidAudioRoutePlatform(context: Context) : AudioRoutePlatform {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
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
            outputs().map { device ->
                val kind = AudioRouteDevices.routeKindForDeviceType(device.type)
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

    override fun isAvailable(routeId: Int): Boolean = routeId == SYSTEM_AUDIO_ROUTE_ID || preferredOutput(routeId) != null

    override fun preferredOutput(routeId: Int): AudioDeviceInfo? = outputs().firstOrNull { it.id == routeId }

    override fun preferredInput(routeId: Int): AudioDeviceInfo? {
        val output = preferredOutput(routeId) ?: return null
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
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
            bluetoothMonitor.stop()
        } finally {
            onDevicesChanged = {}
            if (registered) manager.unregisterAudioDeviceCallback(callback)
            registered = false
        }
    }

    private fun outputs(): List<AudioDeviceInfo> =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { AudioRouteDevices.isSelectableMediaOutput(it.type) }
}
