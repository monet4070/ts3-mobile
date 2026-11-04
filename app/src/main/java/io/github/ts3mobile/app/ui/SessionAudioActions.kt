package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.protocol.ConnectionPhase

@Composable
internal fun SessionAudioActions(
    state: TeamSpeakServiceState,
    onPlaybackMutedChange: (Boolean) -> Unit,
    onAudioRouteSelected: (Int) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenPresence: () -> Unit,
) {
    val connected = state.status.phase == ConnectionPhase.CONNECTED
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { AudioRouteMenu(state.audioRouting, onAudioRouteSelected) }
        IconButton(onClick = onOpenSearch, enabled = connected) {
            AppIcon(UiIcon.Search, stringResource(R.string.discovery_title))
        }
        AwayStatusButton(state.presence, connected, onOpenPresence)
        IconButton(onClick = { onPlaybackMutedChange(!state.playbackMuted) }) {
            AppIcon(
                if (state.playbackMuted) UiIcon.VolumeOff else UiIcon.Volume,
                stringResource(if (state.playbackMuted) R.string.action_unmute_playback else R.string.action_mute_playback),
                tint = if (state.playbackMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AudioRouteMenu(
    routing: AudioRoutingState,
    onRouteSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = routing.selectedRoute.resolveLabel(LocalContext.current)
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            AppIcon(UiIcon.Headphones, stringResource(R.string.action_select_audio_device, label))
            Spacer(Modifier.size(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(4.dp))
            AppIcon(UiIcon.ChevronDown, null, Modifier.size(14.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            routing.routes.forEach { route ->
                DropdownMenuItem(
                    text = { Text(route.resolveLabel(LocalContext.current), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onRouteSelected(route.id)
                    },
                    leadingIcon = {
                        if (route.id == routing.selectedRouteId) AppIcon(UiIcon.Check, null) else Spacer(Modifier.size(20.dp))
                    },
                )
            }
        }
    }
}
