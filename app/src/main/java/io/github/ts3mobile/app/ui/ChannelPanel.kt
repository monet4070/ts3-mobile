package io.github.ts3mobile.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ChannelTree
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelList(
    state: TeamSpeakServiceState,
    onJoinChannel: (Int, String) -> Unit,
    onPoke: (Ts3Participant) -> Unit = {},
) {
    var passwordChannel by remember(state.bookmarkConnection?.token) { mutableStateOf<Ts3Channel?>(null) }
    var expandedChannelIds by rememberSaveable { mutableStateOf(intArrayOf()) }
    val currentChannelId = state.snapshot.currentChannelId
    val rows =
        remember(state.snapshot.channels) {
            ChannelTree.flatten(state.snapshot.channels)
        }
    val participantsByChannel =
        remember(state.snapshot.participants) {
            state.snapshot.participants.groupBy(Ts3Participant::channelId)
        }
    val requestJoin: (Ts3Channel) -> Unit = { channel ->
        if (state.status.phase == ConnectionPhase.CONNECTED && state.switchingChannelId == null &&
            channel.id != currentChannelId && state.snapshot.channels.count { it.id == channel.id } == 1
        ) {
            if (channel.hasPassword) passwordChannel = channel else onJoinChannel(channel.id, "")
        }
    }
    LaunchedEffect(state.status.phase, state.snapshot.channels) {
        if (state.status.phase != ConnectionPhase.CONNECTED ||
            passwordChannel?.let { selected -> state.snapshot.channels.count { it.id == selected.id } != 1 } == true
        ) {
            passwordChannel = null
        }
    }

    LaunchedEffect(currentChannelId) {
        if (currentChannelId != null && currentChannelId !in expandedChannelIds) {
            expandedChannelIds += currentChannelId
        }
    }

    passwordChannel?.let { channel ->
        ChannelPasswordDialog(channel.name, onDismiss = { passwordChannel = null }) { password ->
            if (state.status.phase == ConnectionPhase.CONNECTED && state.snapshot.channels.count { it.id == channel.id } == 1) {
                onJoinChannel(channel.id, password)
            }
            passwordChannel = null
        }
    }

    if (rows.isEmpty()) {
        EmptyList(stringResource(R.string.empty_channels))
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.channel.id }) { row ->
            val isCurrent = row.channel.id == currentChannelId
            val isSwitching = row.channel.id == state.switchingChannelId
            val participants = participantsByChannel[row.channel.id].orEmpty()
            val isExpanded = row.channel.id in expandedChannelIds
            val joinDescription = stringResource(R.string.channel_join_named, row.channel.name)
            val canJoin = !isCurrent && state.switchingChannelId == null && state.status.phase == ConnectionPhase.CONNECTED
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .combinedClickable(
                                onClickLabel =
                                    stringResource(
                                        if (isExpanded) {
                                            R.string.action_collapse_channel
                                        } else {
                                            R.string.action_expand_channel
                                        },
                                    ),
                                onClick = {
                                    expandedChannelIds =
                                        if (isExpanded) {
                                            expandedChannelIds.filterNot { it == row.channel.id }.toIntArray()
                                        } else {
                                            expandedChannelIds + row.channel.id
                                        }
                                },
                                onDoubleClick = { requestJoin(row.channel) },
                            )
                            .semantics {
                                if (canJoin) {
                                    customActions =
                                        listOf(
                                            CustomAccessibilityAction(joinDescription) {
                                                requestJoin(row.channel)
                                                true
                                            },
                                        )
                                }
                            }
                            .padding(
                                start = (8 + row.depth * 20).coerceAtMost(88).dp,
                                end = 16.dp,
                                top = 6.dp,
                                bottom = 6.dp,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(
                        icon =
                            if (isExpanded) {
                                UiIcon.ChevronDown
                            } else {
                                UiIcon.ChevronRight
                            },
                        contentDescription =
                            stringResource(
                                if (isExpanded) R.string.state_expanded else R.string.state_collapsed,
                            ),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    AppIcon(
                        icon = if (row.channel.hasPassword) UiIcon.Lock else UiIcon.Channel,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint =
                            if (row.channel.isDefault || isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = row.channel.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.channel.clientCount.toString(),
                        modifier =
                            Modifier.background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.extraSmall,
                            ).padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(10.dp))
                        AppIcon(
                            UiIcon.Current,
                            contentDescription = stringResource(R.string.label_current_channel),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (isSwitching) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    if (!isCurrent) {
                        TextButton(
                            onClick = { requestJoin(row.channel) },
                            enabled = canJoin,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = joinDescription },
                        ) { Text(stringResource(R.string.action_join)) }
                    }
                }
                if (isExpanded) {
                    participants.forEach { participant ->
                        ChannelParticipantRow(
                            participant = participant,
                            isOwnClient = participant.id == state.snapshot.ownClientId,
                            depth = row.depth,
                            onPoke = { onPoke(participant) },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
