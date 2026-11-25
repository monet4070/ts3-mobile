package io.github.ts3mobile.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.ActualAudioDevice
import io.github.ts3mobile.audio.opus.PlaybackStatus

@Composable
internal fun ActualAudioDevicesPanel(state: TeamSpeakServiceState) {
    val devices = state.actualAudioDevices.activeInput(state.isCapturing)
    val stopped = state.playbackStatus == PlaybackStatus.STOPPED || state.playbackStatus == PlaybackStatus.FAILED
    val output =
        when {
            state.playbackMuted || state.audioRouting.safetyMuted || stopped -> R.string.audio_device_idle
            else -> devices.output.labelId()
        }
    val input = if (devices.recording) devices.input.labelId() else R.string.audio_device_not_recording
    val mute =
        when {
            !devices.recording -> R.string.audio_device_not_recording
            devices.systemMicMuted == true -> R.string.audio_device_muted
            devices.systemMicMuted == false -> R.string.audio_device_not_muted
            else -> R.string.audio_device_unknown
        }
    SettingsSection(stringResource(R.string.audio_device_actual_title), UiIcon.Audio) {
        SettingsReadout(
            stringResource(R.string.audio_device_selected_output),
            stringResource(state.audioRouting.selectedRoute.kind.labelId()),
        )
        SettingsReadout(stringResource(R.string.audio_device_actual_output), stringResource(output))
        SettingsReadout(stringResource(R.string.audio_device_actual_input), stringResource(input))
        SettingsReadout(stringResource(R.string.audio_device_system_mute), stringResource(mute))
    }
}

private fun ActualAudioDevice?.labelId(): Int =
    when (this) {
        null -> R.string.audio_device_unknown
        ActualAudioDevice.BUILTIN_MIC -> R.string.audio_device_builtin_microphone
        ActualAudioDevice.EARPIECE -> R.string.audio_route_earpiece
        ActualAudioDevice.SPEAKER -> R.string.audio_route_speaker
        ActualAudioDevice.WIRED -> R.string.audio_route_wired
        ActualAudioDevice.USB -> R.string.audio_route_usb
        ActualAudioDevice.BLUETOOTH_A2DP -> R.string.audio_device_bluetooth_media
        ActualAudioDevice.BLUETOOTH_SCO -> R.string.audio_device_bluetooth_call
        ActualAudioDevice.BLUETOOTH_OTHER -> R.string.audio_device_bluetooth_other
        ActualAudioDevice.OTHER -> R.string.audio_route_other
    }
