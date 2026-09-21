package io.github.ts3mobile.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ChannelTree
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelList(
    state: TeamSpeakServiceState,
    onJoinChannel: (Int, String) -> Unit,
) {
    var passwordChannel by remember { mutableStateOf<Ts3Channel?>(null) }
    var channelPassword by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
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

    LaunchedEffect(currentChannelId) {
        if (currentChannelId != null && currentChannelId !in expandedChannelIds) {
            expandedChannelIds += currentChannelId
        }
    }

    passwordChannel?.let { channel ->
        AlertDialog(
            onDismissRequest = {
                passwordChannel = null
                channelPassword = ""
                passwordVisible = false
            },
            title = { Text(stringResource(R.string.channel_join_title, channel.name)) },
            text = {
                OutlinedTextField(
                    value = channelPassword,
                    onValueChange = { channelPassword = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.field_channel_password)) },
                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector =
                                    if (passwordVisible) {
                                        Icons.Outlined.VisibilityOff
                                    } else {
                                        Icons.Outlined.Visibility
                                    },
                                contentDescription =
                                    stringResource(
                                        if (passwordVisible) {
                                            R.string.action_hide_password
                                        } else {
                                            R.string.action_show_password
                                        },
                                    ),
                            )
                        }
                    },
                    visualTransformation =
                        if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onJoinChannel(channel.id, channelPassword)
                        passwordChannel = null
                        channelPassword = ""
                        passwordVisible = false
                    },
                ) {
                    Text(stringResource(R.string.action_join))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        passwordChannel = null
                        channelPassword = ""
                        passwordVisible = false
                    },
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
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
                                onDoubleClick = {
                                    if (!isCurrent && state.switchingChannelId == null) {
                                        if (row.channel.hasPassword) {
                                            channelPassword = ""
                                            passwordVisible = false
                                            passwordChannel = row.channel
                                        } else {
                                            onJoinChannel(row.channel.id, "")
                                        }
                                    }
                                },
                            )
                            .padding(
                                start = (8 + row.depth * 20).coerceAtMost(88).dp,
                                end = 16.dp,
                                top = 13.dp,
                                bottom = 13.dp,
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector =
                            if (isExpanded) {
                                Icons.Outlined.KeyboardArrowDown
                            } else {
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight
                            },
                        contentDescription =
                            stringResource(
                                if (isExpanded) R.string.state_expanded else R.string.state_collapsed,
                            ),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (row.channel.hasPassword) Icons.Outlined.Lock else Icons.Outlined.Tag,
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
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
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
                }
                if (isExpanded) {
                    participants.forEach { participant ->
                        ChannelParticipantRow(
                            participant = participant,
                            isOwnClient = participant.id == state.snapshot.ownClientId,
                            depth = row.depth,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        }
    }
}
