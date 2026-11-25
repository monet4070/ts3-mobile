package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.MicrophoneTrialAccess
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.microphoneTrialAccess
import io.github.ts3mobile.app.settings.MicrophonePreferenceField
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.audio.opus.EchoCancellationState
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
internal fun MicrophoneSettingsScreen(
    state: TeamSpeakServiceState,
    snackbar: SnackbarHostState,
    onChange: (MicrophonePreferenceField, MicrophoneSettings) -> Unit,
    onBack: () -> Unit,
    preferenceStatus: MicrophonePreferenceStatus = MicrophonePreferenceStatus.SAVED,
    onCommit: () -> Unit = {},
    onRetry: () -> Unit = {},
    onReset: () -> Unit = {},
    levels: Flow<MicrophoneLevel?> = emptyFlow(),
    onPushToTalkChanged: (Boolean) -> Unit = {},
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit = {},
    microphoneTrialDialog: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
) {
    val commit by rememberUpdatedState(onCommit)
    DisposableEffect(Unit) { onDispose { commit() } }
    val settings = state.microphoneSettings
    val preferenceFailed =
        preferenceStatus == MicrophonePreferenceStatus.READ_FAILED ||
            preferenceStatus == MicrophonePreferenceStatus.WRITE_FAILED
    val volumeLabel = stringResource(R.string.microphone_input_volume)
    val canTest = state.microphoneTrialAccess != MicrophoneTrialAccess.UNAVAILABLE && microphoneTrialDialog != null
    var showTrial by remember(state.status.phase, state.bookmarkConnection?.token, microphoneTrialDialog != null) { mutableStateOf(false) }
    SettingsPage(R.string.microphone_settings, onBack, snackbar) {
        SettingsActionGroup(
            listOf(
                SettingsAction(
                    R.string.microphone_trial_title,
                    UiIcon.Activity,
                    unavailableHint = R.string.settings_trial_wait,
                    guideTarget = GuideAnchor.TEST_MIC,
                    onClick = if (canTest) ({ showTrial = true }) else null,
                ),
            ),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (preferenceFailed) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(12.dp)) { MicrophonePreferenceControls(preferenceStatus, onRetry, onReset) }
                }
            }
            SettingsSection(
                stringResource(R.string.microphone_mode_gate),
                UiIcon.Activity,
                Modifier.testTag("microphone_gate_section"),
            ) {
                MicrophoneTuningCapture(
                    state,
                    levels,
                    onPushToTalkChanged,
                    onMicrophoneModeChanged,
                    onThresholdChange = {
                        onChange(MicrophonePreferenceField.GATE_THRESHOLD, settings.copy(gateThresholdDb = it))
                    },
                    onThresholdCommit = onCommit,
                )
                Text(
                    stringResource(R.string.microphone_gate_visual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsSection(volumeLabel, UiIcon.Mic) {
                Text(
                    stringResource(R.string.microphone_volume_value, settings.volumePercent),
                    style = MaterialTheme.typography.titleMedium,
                )
                MicrophoneInputVolumeSlider(
                    volumePercent = settings.volumePercent,
                    onVolumeChanged = { onChange(MicrophonePreferenceField.VOLUME, settings.copy(volumePercent = it)) },
                    onCommit = onCommit,
                )
                Text(
                    stringResource(R.string.microphone_gain_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsSection(stringResource(R.string.microphone_processing_title), UiIcon.Sliders) {
                SettingsToggleRow(
                    stringResource(R.string.microphone_noise_suppression),
                    settings.noiseSuppression,
                    { onChange(MicrophonePreferenceField.NOISE_SUPPRESSION, settings.copy(noiseSuppression = it)) },
                )
                SettingsToggleRow(
                    stringResource(R.string.microphone_echo_cancellation),
                    settings.echoCancellation,
                    { onChange(MicrophonePreferenceField.ECHO_CANCELLATION, settings.copy(echoCancellation = it)) },
                    detail =
                        stringResource(
                            when (state.echoCancellationState) {
                                EchoCancellationState.PENDING -> R.string.microphone_echo_pending
                                EchoCancellationState.ENABLED -> R.string.microphone_echo_enabled
                                EchoCancellationState.DISABLED -> R.string.microphone_echo_disabled
                                EchoCancellationState.UNAVAILABLE -> R.string.microphone_echo_unavailable
                                EchoCancellationState.FAILED -> R.string.microphone_echo_failed
                            },
                        ),
                )
            }
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!preferenceFailed) MicrophonePreferenceControls(preferenceStatus, onRetry, onReset)
                Text(
                    stringResource(R.string.microphone_settings_lifetime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showTrial) microphoneTrialDialog?.invoke { showTrial = false }
}
