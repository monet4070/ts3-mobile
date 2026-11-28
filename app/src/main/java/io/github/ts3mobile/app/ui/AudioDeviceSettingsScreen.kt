package io.github.ts3mobile.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.AudioInputKind
import io.github.ts3mobile.audio.opus.AudioInputOption
import io.github.ts3mobile.audio.opus.AudioRoutingErrorKind
import io.github.ts3mobile.audio.opus.BuiltInMicrophonePosition

@Composable
internal fun AudioDeviceSettingsScreen(
    state: TeamSpeakServiceState,
    onInputSelected: (Int) -> Unit,
    onOutputSelected: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val routing = state.audioRouting
    val context = LocalContext.current
    SettingsPage(R.string.audio_device_settings_title, onBack) {
        SettingsSection(stringResource(R.string.audio_device_input_title), UiIcon.Mic) {
            if (routing.selectedRoute.bluetoothCall) {
                ListItem(headlineContent = { Text(stringResource(R.string.audio_device_bluetooth_call_mic)) })
            } else {
                Column(Modifier.selectableGroup()) {
                    routing.input.routes.labeledInputs(context).forEach { (device, label) ->
                        key(device.id) {
                            DeviceChoice(label, device.id == routing.input.selectedId) { onInputSelected(device.id) }
                        }
                    }
                }
                routing.input.error?.let { error ->
                    val message =
                        if (error == AudioRoutingErrorKind.DEVICE_DISCONNECTED) {
                            R.string.audio_device_input_disconnected
                        } else {
                            R.string.audio_device_input_unavailable
                        }
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        SettingsSection(stringResource(R.string.audio_device_output_title), UiIcon.Headphones) {
            Column(Modifier.selectableGroup()) {
                routing.routes.forEach { device ->
                    key(device.id) {
                        DeviceChoice(device.resolveLabel(context), device.id == routing.selectedRouteId) { onOutputSelected(device.id) }
                    }
                }
            }
            routing.error?.let { Text(it.resolve(context), color = MaterialTheme.colorScheme.error) }
        }
        ActualAudioDevicesPanel(state)
    }
}

@Composable
private fun DeviceChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected, onClick = null) },
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
    )
}

private fun List<AudioInputOption>.labeledInputs(context: Context): List<Pair<AudioInputOption, String>> {
    val entries = map { it to it.inputLabel(context) }
    val counts = entries.groupingBy { it.second.lowercase() }.eachCount()
    val usedLabels = counts.keys.toMutableSet()
    val occurrences = mutableMapOf<String, Int>()
    return entries.map { (device, label) ->
        val labelKey = label.lowercase()
        val displayedLabel =
            if (counts.getValue(labelKey) > 1) {
                var occurrence = occurrences.getOrDefault(labelKey, 0)
                var candidate: String
                do {
                    occurrence++
                    candidate = context.getString(R.string.audio_device_input_numbered, label, occurrence)
                } while (!usedLabels.add(candidate.lowercase()))
                occurrences[labelKey] = occurrence
                candidate
            } else {
                label
            }
        device to displayedLabel
    }
}

private fun AudioInputOption.inputLabel(context: Context): String {
    val label =
        context.getString(
            when (kind) {
                AudioInputKind.AUTOMATIC -> R.string.audio_device_input_auto
                AudioInputKind.BUILTIN ->
                    when (builtInPosition) {
                        BuiltInMicrophonePosition.BOTTOM -> R.string.audio_device_builtin_microphone_bottom
                        BuiltInMicrophonePosition.BACK -> R.string.audio_device_builtin_microphone_back
                        BuiltInMicrophonePosition.FRONT -> R.string.audio_device_builtin_microphone_front
                        BuiltInMicrophonePosition.TOP -> R.string.audio_device_builtin_microphone_top
                        null -> R.string.audio_device_builtin_microphone
                    }
                AudioInputKind.WIRED -> R.string.audio_route_wired
                AudioInputKind.USB -> R.string.audio_route_usb
                AudioInputKind.LE_AUDIO -> R.string.audio_device_bluetooth_le_mic
                AudioInputKind.OTHER -> R.string.audio_route_other
            },
        )
    val name = deviceName?.takeUnless { it.equals(label, ignoreCase = true) }
    return name?.let { context.getString(R.string.audio_route_with_device, label, it) } ?: label
}
