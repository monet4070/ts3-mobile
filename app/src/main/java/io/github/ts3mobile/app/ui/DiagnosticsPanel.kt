package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.DiagnosticsSnapshot

@Composable
internal fun DiagnosticsPanel(
    diagnostics: DiagnosticsSnapshot,
    onCopyDiagnostics: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.diagnostics_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onCopyDiagnostics) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_copy))
            }
        }

        Spacer(Modifier.height(2.dp))
        HorizontalDivider()

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
private fun DiagnosticMetric(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(34.dp),
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
