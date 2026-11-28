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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.settings.PlaybackPreferenceState
import io.github.ts3mobile.audio.opus.ReceiveDelayProfile

@Composable
internal fun PlaybackSettingsScreen(
    state: PlaybackPreferenceState,
    onSelect: (ReceiveDelayProfile) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    session: TeamSpeakServiceState = TeamSpeakServiceState(),
) {
    SettingsPage(R.string.playback_settings_title, onBack) {
        SettingsSection(stringResource(R.string.playback_profile_title), UiIcon.Headphones) {
            Text(
                stringResource(R.string.playback_settings_lifetime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup().guideAnchor(GuideAnchor.PLAYBACK_PROFILE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlaybackProfileOption(
                    R.string.playback_profile_stability,
                    R.string.playback_profile_stability_summary,
                    state,
                    ReceiveDelayProfile.STABILITY,
                    onSelect,
                )
                PlaybackProfileOption(
                    R.string.playback_profile_balanced,
                    R.string.playback_profile_balanced_summary,
                    state,
                    ReceiveDelayProfile.BALANCED,
                    onSelect,
                )
            }
            when {
                state.busy ->
                    FeatureNotice(
                        UiIcon.Refresh,
                        stringResource(if (state.profile == null) R.string.playback_loading else R.string.playback_saving),
                    )
                state.failed && state.profile == null -> {
                    FeatureNotice(UiIcon.Info, stringResource(R.string.playback_read_failed), error = true)
                    FeatureAction(UiIcon.Refresh, stringResource(R.string.action_retry_control), onRetry)
                }
                state.failed -> FeatureNotice(UiIcon.Info, stringResource(R.string.playback_write_failed), error = true)
            }
        }
        ActualAudioDevicesPanel(session)
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
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .selectable(selected, enabled = enabled, role = Role.RadioButton) { onSelect(profile) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
