package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.ParticipantAudioSettings
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.audioControlKey
import io.github.ts3mobile.protocol.Ts3Participant
import kotlin.math.roundToInt

@Composable
internal fun ParticipantList(
    state: TeamSpeakServiceState,
    onMutedChange: (String, Boolean) -> Unit,
    onVolumeChange: (String, Int) -> Unit,
    onPrivateMessage: (Int) -> Unit = {},
    onPoke: (Ts3Participant) -> Unit = {},
) {
    val volumeLabelMinWidth = 44.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val channelsById =
        remember(state.snapshot.channels) {
            state.snapshot.channels.associateBy { it.id }
        }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    if (state.snapshot.participants.isEmpty()) {
        EmptyList(stringResource(R.string.empty_participants))
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        // Several connected clients may share an identity; list rows are client instances.
        items(state.snapshot.participants, key = { it.id }) { participant ->
            val key = participant.audioControlKey()
            val settings = state.participantAudioSettings[key] ?: ParticipantAudioSettings()
            val isOwnClient = participant.id == state.snapshot.ownClientId
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(
                        icon =
                            when {
                                participant.isTalking -> UiIcon.Audio
                                participant.isInputMuted -> UiIcon.MicOff
                                participant.isOutputMuted -> UiIcon.VolumeOff
                                else -> UiIcon.User
                            },
                        accented = participant.isTalking,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text =
                                if (participant.isAway) {
                                    stringResource(
                                        R.string.participant_away_name,
                                        participant.nickname,
                                    )
                                } else {
                                    participant.nickname
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                participantAudioDetail(
                                    channelName = channelsById[participant.channelId]?.name.orEmpty(),
                                    settings = settings,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (participant.isAway && participant.awayMessage.isNotBlank()) {
                            Text(
                                participant.awayMessage,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!isOwnClient) {
                        ParticipantChatAction(participant.nickname, { onPrivateMessage(participant.id) }, { onPoke(participant) })
                        IconButton(onClick = { onMutedChange(key, !settings.muted) }) {
                            AppIcon(
                                icon =
                                    if (settings.muted) {
                                        UiIcon.VolumeOff
                                    } else {
                                        UiIcon.Volume
                                    },
                                contentDescription =
                                    stringResource(
                                        if (settings.muted) {
                                            R.string.action_unmute_participant
                                        } else {
                                            R.string.action_mute_participant
                                        },
                                        participant.nickname,
                                    ),
                            )
                        }
                        IconButton(
                            onClick = {
                                expandedKey = if (expandedKey == key) null else key
                            },
                        ) {
                            AppIcon(
                                icon = UiIcon.Sliders,
                                contentDescription =
                                    stringResource(R.string.action_adjust_volume, participant.nickname),
                                tint =
                                    if (settings.volumePercent != 100) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }
                if (!isOwnClient && expandedKey == key) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(start = 54.dp, end = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Slider(
                            value = settings.volumePercent.toFloat(),
                            onValueChange = { value ->
                                val volume = (value / 5f).roundToInt() * 5
                                onVolumeChange(key, volume)
                            },
                            modifier = Modifier.weight(1f),
                            valueRange = 0f..200f,
                            steps = 39,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${settings.volumePercent}%",
                            modifier = Modifier.widthIn(min = volumeLabelMinWidth),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun participantAudioDetail(
    channelName: String,
    settings: ParticipantAudioSettings,
): String =
    when {
        settings.muted -> stringResource(R.string.participant_channel_muted, channelName)
        settings.volumePercent != 100 -> "$channelName · ${settings.volumePercent}%"
        else -> channelName
    }

@Composable
internal fun EmptyList(label: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
