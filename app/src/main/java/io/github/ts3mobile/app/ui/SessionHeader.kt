package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.AudioRoutingState

@Composable
internal fun SessionHeader(
    state: TeamSpeakServiceState,
    onDisconnect: () -> Unit,
    onPlaybackMutedChange: (Boolean) -> Unit,
    onAudioRouteSelected: (Int) -> Unit,
) {
    Panel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(UiIcon.Server)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.serverLabel.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        pluralStringResource(R.plurals.connected_channel_count, state.snapshot.channels.size, state.snapshot.channels.size),
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
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { AudioRouteMenu(state.audioRouting, onAudioRouteSelected) }
            IconButton(onClick = { onPlaybackMutedChange(!state.playbackMuted) }) {
                AppIcon(
                    if (state.playbackMuted) UiIcon.VolumeOff else UiIcon.Volume,
                    stringResource(if (state.playbackMuted) R.string.action_unmute_playback else R.string.action_mute_playback),
                    tint = if (state.playbackMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDisconnect) {
                AppIcon(UiIcon.Power, stringResource(R.string.action_disconnect), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AudioRouteMenu(
    routing: AudioRoutingState,
    onRouteSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        androidx.compose.material3.TextButton(onClick = { expanded = true }) {
            AppIcon(
                UiIcon.Headphones,
                contentDescription =
                    stringResource(
                        R.string.action_select_audio_device,
                        routing.selectedRoute.resolveLabel(LocalContext.current),
                    ),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                routing.selectedRoute.resolveLabel(LocalContext.current),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
            Spacer(Modifier.size(4.dp))
            AppIcon(UiIcon.ChevronDown, null, Modifier.size(14.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            routing.routes.forEach { route ->
                val selected = route.id == routing.selectedRouteId
                DropdownMenuItem(
                    text = {
                        Text(
                            text = route.resolveLabel(LocalContext.current),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        expanded = false
                        onRouteSelected(route.id)
                    },
                    leadingIcon = {
                        if (selected) {
                            AppIcon(UiIcon.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}
