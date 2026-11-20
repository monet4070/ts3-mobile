package io.github.ts3mobile.audio.opus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler

internal class BluetoothRouteMonitor(
    private val context: Context,
    private val handler: Handler,
) {
    private var registered = false
    private var onBecomingNoisy: () -> Unit = {}
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onBecomingNoisy()
            }
        }

    fun start(onBecomingNoisy: () -> Unit) {
        this.onBecomingNoisy = onBecomingNoisy
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        registered = true
    }

    fun stop() {
        onBecomingNoisy = {}
        if (registered) context.unregisterReceiver(receiver)
        registered = false
    }
}
