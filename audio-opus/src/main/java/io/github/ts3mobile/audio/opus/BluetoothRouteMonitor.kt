package io.github.ts3mobile.audio.opus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/** Watches route loss even when the headset remains in the available-device list. */
internal class BluetoothRouteMonitor(
    private val context: Context,
    private val manager: AudioManager,
    private val handler: Handler,
) {
    @Volatile private var expected = false

    @Volatile private var confirmed = false

    @Volatile private var scoConnected = false
    private var registered = false
    private var onLost: () -> Unit = {}
    private var communicationListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private val timeout =
        Runnable {
            if (expected && !confirmed) onLost()
        }
    private val receiver =
        object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    if (expected || confirmed) onLost()
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            scoConnected = true
                            observe(true)
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            scoConnected = false
                            if (!isInitialStickyBroadcast) observe(false)
                        }
                    }
                }
            }
        }

    fun start(onLost: () -> Unit) {
        this.onLost = onLost
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY).apply { addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        registered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener =
                AudioManager.OnCommunicationDeviceChangedListener { device ->
                    // Preserve a brief route-loss event even if Bluetooth has already reconnected.
                    observe(isBluetooth(device))
                }
            manager.addOnCommunicationDeviceChangedListener({ handler.post(it) }, listener)
            communicationListener = listener
        }
    }

    fun expectBluetooth(expected: Boolean) {
        this.expected = expected
        confirmed = expected && scoConnected
        handler.removeCallbacks(timeout)
        if (expected) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) confirmed = isBluetooth(manager.communicationDevice)
            handler.postDelayed(timeout, 5_000L)
        }
    }

    fun stop() {
        expected = false
        confirmed = false
        scoConnected = false
        handler.removeCallbacks(timeout)
        communicationListener?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.removeOnCommunicationDeviceChangedListener(
                    it,
                )
            }
        }
        communicationListener = null
        if (registered) context.unregisterReceiver(receiver)
        registered = false
    }

    private fun observe(bluetooth: Boolean) {
        if (!expected) return
        val wasConfirmed = confirmed
        confirmed = bluetooth
        if (bluetooth) {
            handler.removeCallbacks(timeout)
        } else if (wasConfirmed) {
            onLost()
        }
    }

    private fun isBluetooth(device: AudioDeviceInfo?): Boolean =
        device?.let { AudioRouteDevices.routeKindForDeviceType(it.type) } == AudioRouteKind.BLUETOOTH
}
