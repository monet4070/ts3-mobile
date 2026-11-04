package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState

@Composable
internal fun SessionHeader(
    state: TeamSpeakServiceState,
    onDisconnect: () -> Unit,
    onPlaybackMutedChange: (Boolean) -> Unit,
    onAudioRouteSelected: (Int) -> Unit,
    compact: Boolean = false,
    onOpenSearch: () -> Unit = {},
    onOpenPresence: () -> Unit = {},
) {
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showIcon = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.3f
            Row(Modifier.fillMaxWidth().padding(if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showIcon) {
                    IconTile(UiIcon.Server, Modifier.size(36.dp))
                    Spacer(Modifier.size(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.serverLabel.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(
                            pluralStringResource(
                                R.plurals.connected_channel_count,
                                state.snapshot.channels.size,
                                state.snapshot.channels.size,
                            ),
                            pluralStringResource(
                                R.plurals.connected_online_count,
                                state.snapshot.participants.size,
                                state.snapshot.participants.size,
                            ),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(
                    onClick = onDisconnect,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (showIcon) {
                        AppIcon(UiIcon.Power, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(stringResource(R.string.action_disconnect), maxLines = 1, softWrap = false)
                }
            }
        }
        if (!compact) {
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SessionAudioActions(state, onPlaybackMutedChange, onAudioRouteSelected, onOpenSearch, onOpenPresence)
        }
    }
}
