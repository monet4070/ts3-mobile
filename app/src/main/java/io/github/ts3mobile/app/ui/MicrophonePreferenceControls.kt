package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MicrophonePreferenceControls(
    status: MicrophonePreferenceStatus,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    Text(
        stringResource(
            when (status) {
                MicrophonePreferenceStatus.LOADING -> R.string.microphone_preferences_loading
                MicrophonePreferenceStatus.PENDING -> R.string.microphone_preferences_pending
                MicrophonePreferenceStatus.SAVING -> R.string.microphone_preferences_saving
                MicrophonePreferenceStatus.SAVED -> R.string.microphone_preferences_saved
                MicrophonePreferenceStatus.READ_FAILED -> R.string.microphone_preferences_read_failed
                MicrophonePreferenceStatus.WRITE_FAILED -> R.string.microphone_preferences_write_failed
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color =
            if (status == MicrophonePreferenceStatus.READ_FAILED || status == MicrophonePreferenceStatus.WRITE_FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == MicrophonePreferenceStatus.READ_FAILED || status == MicrophonePreferenceStatus.WRITE_FAILED) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.microphone_preferences_retry)) }
        }
        OutlinedButton(onClick = onReset) { Text(stringResource(R.string.microphone_preferences_reset)) }
    }
}
