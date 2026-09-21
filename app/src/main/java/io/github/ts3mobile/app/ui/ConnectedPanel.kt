package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.AudioRoutingState

@Composable
internal fun ConnectedContent(
    state: TeamSpeakServiceState,
    onDisconnect: () -> Unit,
    onPlaybackMutedChange: (Boolean) -> Unit,
    onParticipantMutedChange: (String, Boolean) -> Unit,
    onParticipantVolumeChange: (String, Int) -> Unit,
    onAudioRouteSelected: (Int) -> Unit,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
    onPushToTalkChanged: (Boolean) -> Unit,
    onJoinChannel: (Int, String) -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.serverLabel.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.connected_summary,
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
                                state.audioRouting.selectedRoute.resolveLabel(LocalContext.current),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AudioRouteMenu(
                    routing = state.audioRouting,
                    onRouteSelected = onAudioRouteSelected,
                )
                IconButton(onClick = { onPlaybackMutedChange(!state.playbackMuted) }) {
                    Icon(
                        imageVector =
                            if (state.playbackMuted) {
                                Icons.AutoMirrored.Outlined.VolumeOff
                            } else {
                                Icons.AutoMirrored.Outlined.VolumeUp
                            },
                        contentDescription =
                            stringResource(
                                if (state.playbackMuted) {
                                    R.string.action_unmute_playback
                                } else {
                                    R.string.action_mute_playback
                                },
                            ),
                    )
                }
                IconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Default.PowerSettingsNew,
                        contentDescription = stringResource(R.string.action_disconnect),
                    )
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.tab_channels)) },
                icon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.tab_participants)) },
                icon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text(stringResource(R.string.tab_diagnostics)) },
                icon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
            )
        }

        Box(Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ChannelList(state, onJoinChannel)
                1 ->
                    ParticipantList(
                        state = state,
                        onMutedChange = onParticipantMutedChange,
                        onVolumeChange = onParticipantVolumeChange,
                    )
                else ->
                    DiagnosticsPanel(
                        diagnostics = state.diagnostics,
                        onCopyDiagnostics = onCopyDiagnostics,
                    )
            }
        }

        MicrophoneControl(
            mode = state.microphoneMode,
            isTransmitting = state.isTransmitting,
            onMicrophoneModeChanged = onMicrophoneModeChanged,
            onPushToTalkChanged = onPushToTalkChanged,
        )
    }
}

@Composable
private fun AudioRouteMenu(
    routing: AudioRoutingState,
    onRouteSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.Headphones,
                contentDescription =
                    stringResource(
                        R.string.action_select_audio_device,
                        routing.selectedRoute.resolveLabel(LocalContext.current),
                    ),
            )
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
                            Icon(Icons.Outlined.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}
