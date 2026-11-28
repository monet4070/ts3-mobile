package io.github.ts3mobile.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.ts3mobile.audio.opus.ActualAudioDevice
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActualAudioDevices(
    val output: ActualAudioDevice? = null,
    val recording: Boolean = false,
    val input: ActualAudioDevice? = null,
    val systemMicMuted: Boolean? = null,
) {
    fun activeInput(capturing: Boolean): ActualAudioDevices =
        if (capturing && recording) this else copy(recording = false, input = null, systemMicMuted = null)
}

internal class AudioDeviceReadout(
    private val context: Context,
    scope: CoroutineScope,
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    private val player: OpusAudioPlayer,
) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val muteChanges =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateMute()
        }

    private val observesMute = Build.VERSION.SDK_INT >= 28
    private val muteJob =
        scope.launch {
            state.map { it.isCapturing && it.actualAudioDevices.recording }.distinctUntilChanged().collect { updateMute() }
        }

    init {
        player.setOutputDeviceObserver { device ->
            state.update { it.copy(actualAudioDevices = it.actualAudioDevices.copy(output = device)) }
        }
        if (observesMute) {
            ContextCompat.registerReceiver(
                context,
                muteChanges,
                IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun updateMute() {
        val watched = state.value.let { it.isCapturing && it.actualAudioDevices.recording }
        val mute =
            if (!watched) {
                null
            } else {
                try {
                    audio?.isMicrophoneMute
                } catch (_: RuntimeException) {
                    null
                }
            }
        state.update { current ->
            val reading = current.actualAudioDevices.activeInput(current.isCapturing)
            current.copy(actualAudioDevices = reading.copy(systemMicMuted = if (reading.recording) mute else null))
        }
    }

    override fun close() {
        player.setOutputDeviceObserver(null)
        muteJob.cancel()
        if (observesMute) context.unregisterReceiver(muteChanges)
    }
}
