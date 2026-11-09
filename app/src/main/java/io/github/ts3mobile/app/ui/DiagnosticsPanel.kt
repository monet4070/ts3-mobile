package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledTonalButton(
            onClick = onMarkAudioIssue,
            enabled = audio.sessions > 0,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) { Text(stringResource(R.string.diagnostics_audio_mark)) }
        DiagnosticExportButtons(onCopyDiagnostics, onSaveDiagnostics)
        SettingsSection(stringResource(R.string.diagnostics_audio_title), UiIcon.Activity) {
            Text(stringResource(R.string.diagnostics_audio_retention), style = MaterialTheme.typography.bodySmall)
            DiagnosticMetric(stringResource(R.string.diagnostics_audio_sessions), audio.sessions.toString())
            DiagnosticMetric(stringResource(R.string.diagnostics_audio_samples), audio.savedSamples.toString())
            DiagnosticMetric(
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

        Spacer(Modifier.heightIn(min = 2.dp))
        HorizontalDivider()
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.diagnostics_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DiagnosticMetric(stringResource(R.string.diagnostics_uptime), formatDuration(diagnostics.uptimeMs))
        DiagnosticMetric(stringResource(R.string.diagnostics_connection_attempts), diagnostics.connectionAttempts.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_reconnect_attempts), diagnostics.reconnectAttempts.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_successful_connections), diagnostics.successfulConnections.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_failed_connections), diagnostics.failedConnections.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_retryable_failures), diagnostics.retryableFailures.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_voice_frames_received), diagnostics.voiceFramesReceived.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_voice_frames_dropped), diagnostics.voiceFramesDropped.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_microphone_errors), diagnostics.microphoneErrors.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_channel_join_attempts), diagnostics.channelJoinAttempts.toString())
        DiagnosticMetric(stringResource(R.string.diagnostics_channel_join_failures), diagnostics.channelJoinFailures.toString())
        DiagnosticMetric(
            stringResource(R.string.diagnostics_last_failure),
            diagnostics.lastFailure?.name ?: stringResource(R.string.diagnostics_none),
        )
    }
}

@Composable
private fun DiagnosticExportButtons(
    onCopy: () -> Unit,
    onSave: () -> Unit,
) {
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagnosticExportButton(R.string.action_copy, onCopy, Modifier.fillMaxWidth())
            DiagnosticExportButton(R.string.diagnostics_save, onSave, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagnosticExportButton(R.string.action_copy, onCopy, Modifier.weight(1f))
            DiagnosticExportButton(R.string.diagnostics_save, onSave, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DiagnosticExportButton(
    label: Int,
    action: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(
        onClick = action,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) { Text(stringResource(label)) }
}

@Composable
private fun DiagnosticMetric(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
