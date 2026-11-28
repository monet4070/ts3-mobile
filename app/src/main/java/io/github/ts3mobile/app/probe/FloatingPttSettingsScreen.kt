package io.github.ts3mobile.app.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.AppIcon
import io.github.ts3mobile.app.ui.GuideAnchor
import io.github.ts3mobile.app.ui.SettingsAction
import io.github.ts3mobile.app.ui.SettingsActionGroup
import io.github.ts3mobile.app.ui.SettingsChoiceCard
import io.github.ts3mobile.app.ui.SettingsPage
import io.github.ts3mobile.app.ui.SettingsSection
import io.github.ts3mobile.app.ui.SettingsToggleRow
import io.github.ts3mobile.app.ui.UiIcon
import io.github.ts3mobile.app.ui.guideAnchor

@Composable
internal fun FloatingPttSettingsScreen(
    overlayAllowed: Boolean,
    channelReady: Boolean,
    onBack: () -> Unit,
    onPermissionSettings: () -> Unit,
    onShow: () -> Unit,
    autoShow: Boolean,
    onAutoShowChanged: (Boolean) -> Unit,
    style: FloatingPttStyle,
    onStyleChanged: (FloatingPttStyle) -> Unit,
) {
    SettingsPage(R.string.settings_floating_ptt, onBack) {
        SettingsSection(stringResource(R.string.ptt_style_title), UiIcon.FloatingPtt) {
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingPttStyle.entries.forEach { candidate ->
                    val title = if (candidate == FloatingPttStyle.STANDARD) R.string.ptt_standard_style else R.string.ptt_minimal_style
                    SettingsChoiceCard(stringResource(title), style == candidate, preview = { FloatingPttPreview(candidate) }) {
                        onStyleChanged(candidate)
                    }
                }
            }
        }
        SettingsSection(stringResource(R.string.ptt_settings_display), UiIcon.FloatingPtt) {
            SettingsToggleRow(
                stringResource(R.string.ptt_auto_show),
                autoShow,
                onAutoShowChanged,
                detail = stringResource(R.string.ptt_auto_show_summary),
                modifier = Modifier.guideAnchor(GuideAnchor.PTT_AUTOSHOW),
            )
        }
        SettingsSection(stringResource(R.string.ptt_current_session), UiIcon.Channel) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (channelReady) R.string.ptt_session_ready else R.string.ptt_session_disconnected),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.ptt_settings_behavior),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onShow, Modifier.fillMaxWidth().guideAnchor(GuideAnchor.PTT_SHOW), enabled = channelReady && overlayAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppIcon(UiIcon.FloatingPtt, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.notification_show_floating_ptt))
                }
            }
        }
        SettingsActionGroup(
            listOf(
                SettingsAction(
                    R.string.ptt_overlay_permission,
                    UiIcon.Shield,
                    detail = if (overlayAllowed) R.string.ptt_overlay_allowed else R.string.ptt_overlay_needed,
                    onClick = onPermissionSettings,
                ),
            ),
            R.string.ptt_settings_permissions,
        )
    }
}

@Composable
private fun FloatingPttPreview(style: FloatingPttStyle) {
    Surface(
        Modifier.size(width = 62.dp, height = 44.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (style == FloatingPttStyle.STANDARD) AppIcon(UiIcon.Channel, null, Modifier.size(12.dp))
            AppIcon(UiIcon.Audio, null, Modifier.size(12.dp))
            AppIcon(UiIcon.Mic, null, Modifier.size(16.dp))
        }
    }
}
