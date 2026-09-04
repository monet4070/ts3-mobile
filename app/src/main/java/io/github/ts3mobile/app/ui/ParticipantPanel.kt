package io.github.ts3mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
) {
    val channelsById =
        remember(state.snapshot.channels) {
            state.snapshot.channels.associateBy { it.id }
        }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    if (state.snapshot.participants.isEmpty()) {
        EmptyList("没有可见用户")
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.snapshot.participants, key = { it.audioControlKey() }) { participant ->
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
                    Icon(
                        imageVector =
                            when {
                                participant.isTalking -> Icons.Outlined.GraphicEq
                                participant.isInputMuted -> Icons.Outlined.MicOff
                                participant.isOutputMuted -> Icons.AutoMirrored.Outlined.VolumeOff
                                else -> Icons.Outlined.Person
                            },
                        contentDescription = null,
                        tint =
                            if (participant.isTalking) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = participant.nickname,
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
                    }
                    if (!isOwnClient) {
                        IconButton(onClick = { onMutedChange(key, !settings.muted) }) {
                            Icon(
                                imageVector =
                                    if (settings.muted) {
                                        Icons.AutoMirrored.Outlined.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Outlined.VolumeUp
                                    },
                                contentDescription =
                                    if (settings.muted) {
                                        "取消静音${participant.nickname}"
                                    } else {
                                        "静音${participant.nickname}"
                                    },
                            )
                        }
                        IconButton(
                            onClick = {
                                expandedKey = if (expandedKey == key) null else key
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "调整${participant.nickname}的音量",
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
                                .height(52.dp)
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
                            modifier = Modifier.width(52.dp),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        }
    }
}

private fun participantAudioDetail(
    channelName: String,
    settings: ParticipantAudioSettings,
): String =
    when {
        settings.muted -> "$channelName · 已静音"
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

@Composable
internal fun ChannelParticipantRow(
    participant: Ts3Participant,
    isOwnClient: Boolean,
    depth: Int,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .padding(
                    start = (48 + depth * 20).coerceAtMost(112).dp,
                    end = 16.dp,
                    top = 9.dp,
                    bottom = 9.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when {
                    participant.isTalking -> Icons.Outlined.GraphicEq
                    participant.isInputMuted -> Icons.Outlined.MicOff
                    participant.isOutputMuted -> Icons.AutoMirrored.Outlined.VolumeOff
                    else -> Icons.Outlined.Person
                },
            contentDescription =
                when {
                    participant.isTalking -> "正在说话"
                    participant.isInputMuted -> "麦克风静音"
                    participant.isOutputMuted -> "扬声器静音"
                    else -> null
                },
            modifier = Modifier.size(19.dp),
            tint =
                if (participant.isTalking) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = participant.nickname,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isOwnClient) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isOwnClient) {
            Text(
                text = "我",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
