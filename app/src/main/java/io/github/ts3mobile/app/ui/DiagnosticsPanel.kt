package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.AudioDiagnosticStatus
import io.github.ts3mobile.app.service.DiagnosticsSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DiagnosticsPanel(
    diagnostics: DiagnosticsSnapshot,
    onCopyDiagnostics: () -> Unit,
    audio: AudioDiagnosticStatus = AudioDiagnosticStatus(),
    onSaveDiagnostics: () -> Unit = {},
    onMarkAudioIssue: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(stringResource(R.string.diagnostics_tools_title), UiIcon.FileText) {
            FeatureAction(
                UiIcon.Activity,
                stringResource(R.string.diagnostics_audio_mark),
                onMarkAudioIssue,
                modifier = Modifier.fillMaxWidth().guideAnchor(GuideAnchor.DIAGNOSTIC_MARK),
                enabled = audio.sessions > 0,
                style = FeatureActionStyle.PRIMARY,
            )
            DiagnosticExportButtons(onCopyDiagnostics, onSaveDiagnostics)
            Text(
                stringResource(R.string.diagnostics_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsSection(stringResource(R.string.diagnostics_audio_title), UiIcon.Activity) {
            Text(stringResource(R.string.diagnostics_audio_retention), style = MaterialTheme.typography.bodySmall)
            SettingsReadout(stringResource(R.string.diagnostics_audio_sessions), audio.sessions.toString())
            SettingsReadout(stringResource(R.string.diagnostics_audio_samples), audio.savedSamples.toString())
            SettingsReadout(
                stringResource(R.string.diagnostics_audio_last_saved),
                if (audio.lastPersistedTimeMs > 0) {
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(audio.lastPersistedTimeMs))
                } else {
                    stringResource(R.string.diagnostics_none)
                },
            )
            if (audio.failedWrites > 0 || audio.invalidRecords > 0) {
                Text(
                    stringResource(R.string.diagnostics_audio_storage_error, audio.failedWrites, audio.invalidRecords),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (audio.marks > 0) {
                Text(
                    stringResource(R.string.diagnostics_audio_marks, audio.marks),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingsSection(stringResource(R.string.diagnostics_title), UiIcon.Server) {
            SettingsReadout(stringResource(R.string.diagnostics_uptime), formatDuration(diagnostics.uptimeMs))
            SettingsReadout(stringResource(R.string.diagnostics_connection_attempts), diagnostics.connectionAttempts.toString())
            SettingsReadout(stringResource(R.string.diagnostics_reconnect_attempts), diagnostics.reconnectAttempts.toString())
            SettingsReadout(stringResource(R.string.diagnostics_successful_connections), diagnostics.successfulConnections.toString())
            SettingsReadout(stringResource(R.string.diagnostics_failed_connections), diagnostics.failedConnections.toString())
            SettingsReadout(stringResource(R.string.diagnostics_retryable_failures), diagnostics.retryableFailures.toString())
            SettingsReadout(stringResource(R.string.diagnostics_voice_frames_received), diagnostics.voiceFramesReceived.toString())
            SettingsReadout(stringResource(R.string.diagnostics_voice_frames_dropped), diagnostics.voiceFramesDropped.toString())
            SettingsReadout(stringResource(R.string.diagnostics_microphone_errors), diagnostics.microphoneErrors.toString())
            SettingsReadout(stringResource(R.string.diagnostics_channel_join_attempts), diagnostics.channelJoinAttempts.toString())
            SettingsReadout(stringResource(R.string.diagnostics_channel_join_failures), diagnostics.channelJoinFailures.toString())
            SettingsReadout(
                stringResource(R.string.diagnostics_last_failure),
                diagnostics.lastFailure?.name ?: stringResource(R.string.diagnostics_none),
            )
        }
    }
}

@Composable
private fun DiagnosticExportButtons(
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureAction(UiIcon.Copy, stringResource(R.string.action_copy), onCopy, Modifier.fillMaxWidth())
            FeatureAction(UiIcon.Download, stringResource(R.string.diagnostics_save), onSave, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureAction(UiIcon.Copy, stringResource(R.string.action_copy), onCopy, Modifier.weight(1f))
            FeatureAction(UiIcon.Download, stringResource(R.string.diagnostics_save), onSave, Modifier.weight(1f))
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
