package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.settings.PlaybackPreferenceState
import io.github.ts3mobile.audio.opus.ReceiveDelayProfile

@Composable
internal fun PlaybackSettingsScreen(
    state: PlaybackPreferenceState,
    onSelect: (ReceiveDelayProfile) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(R.string.playback_settings_title, onBack) {
        Text(stringResource(R.string.playback_settings_lifetime), style = MaterialTheme.typography.bodyMedium)
        Column(Modifier.selectableGroup()) {
            PlaybackProfileOption(
                R.string.playback_profile_balanced,
                R.string.playback_profile_balanced_summary,
                state,
                ReceiveDelayProfile.BALANCED,
                onSelect,
            )
            PlaybackProfileOption(
                R.string.playback_profile_stability,
                R.string.playback_profile_stability_summary,
                state,
                ReceiveDelayProfile.STABILITY,
                onSelect,
            )
        }
        when {
            state.busy -> Text(stringResource(if (state.profile == null) R.string.playback_loading else R.string.playback_saving))
            state.failed && state.profile == null -> {
                Text(stringResource(R.string.playback_read_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry_control)) }
            }
            state.failed -> Text(stringResource(R.string.playback_write_failed), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PlaybackProfileOption(
    title: Int,
    summary: Int,
    state: PlaybackPreferenceState,
    profile: ReceiveDelayProfile,
    onSelect: (ReceiveDelayProfile) -> Unit,
) {
    val selected = state.profile == profile
    val enabled = state.profile != null && !state.busy
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton) { onSelect(profile) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
