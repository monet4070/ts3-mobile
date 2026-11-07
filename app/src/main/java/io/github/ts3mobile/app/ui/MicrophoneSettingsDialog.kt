package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.settings.MicrophonePreferenceField
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.audio.opus.EchoCancellationState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlin.math.roundToInt

@Composable
internal fun MicrophoneSettingsDialog(
    state: TeamSpeakServiceState,
    onChange: (MicrophonePreferenceField, MicrophoneSettings) -> Unit,
    onDismiss: () -> Unit,
    preferenceStatus: MicrophonePreferenceStatus = MicrophonePreferenceStatus.SAVED,
    onCommit: () -> Unit = {},
    onRetry: () -> Unit = {},
    onReset: () -> Unit = {},
) {
    val commit by rememberUpdatedState(onCommit)
    DisposableEffect(Unit) { onDispose { commit() } }
    val settings = state.microphoneSettings
    val volumeLabel = stringResource(R.string.microphone_input_volume)
    val thresholdLabel = stringResource(R.string.microphone_gate_threshold)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.microphone_settings)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.microphone_settings_lifetime), style = MaterialTheme.typography.bodySmall)
                MicrophonePreferenceControls(preferenceStatus, onRetry, onReset)
                Text(stringResource(R.string.microphone_volume_value, settings.volumePercent))
                Slider(
                    value = settings.volumePercent.toFloat(),
                    onValueChange = {
                        onChange(
                            MicrophonePreferenceField.VOLUME,
                            settings.copy(volumePercent = it.roundToInt().coerceIn(0, 200)),
                        )
                    },
                    onValueChangeFinished = onCommit,
                    valueRange = 0f..200f,
                    modifier = Modifier.semantics { contentDescription = volumeLabel },
                )
                Text(stringResource(R.string.microphone_gain_hint), style = MaterialTheme.typography.bodySmall)
                ProcessingSwitch(stringResource(R.string.microphone_noise_suppression), settings.noiseSuppression) {
                    onChange(MicrophonePreferenceField.NOISE_SUPPRESSION, settings.copy(noiseSuppression = it))
                }
                ProcessingSwitch(stringResource(R.string.microphone_echo_cancellation), settings.echoCancellation) {
                    onChange(MicrophonePreferenceField.ECHO_CANCELLATION, settings.copy(echoCancellation = it))
                }
                Text(
                    stringResource(
                        when (state.echoCancellationState) {
                            EchoCancellationState.PENDING -> R.string.microphone_echo_pending
                            EchoCancellationState.ENABLED -> R.string.microphone_echo_enabled
                            EchoCancellationState.DISABLED -> R.string.microphone_echo_disabled
                            EchoCancellationState.UNAVAILABLE -> R.string.microphone_echo_unavailable
                            EchoCancellationState.FAILED -> R.string.microphone_echo_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.microphone_gate_value, settings.gateThresholdDb))
                Slider(
                    value = settings.gateThresholdDb.toFloat(),
                    onValueChange = {
                        onChange(
                            MicrophonePreferenceField.GATE_THRESHOLD,
                            settings.copy(gateThresholdDb = it.roundToInt().coerceIn(-60, -15)),
                        )
                    },
                    onValueChangeFinished = onCommit,
                    valueRange = -60f..-15f,
                    modifier = Modifier.semantics { contentDescription = thresholdLabel },
                )
                Text(stringResource(R.string.microphone_gate_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.microphone_gate_privacy), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun ProcessingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
