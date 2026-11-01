package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.discovery.searchSession
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ControlStatus
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant

@Composable
internal fun SessionSearchDialog(
    state: TeamSpeakServiceState,
    onInfo: (Int) -> Unit,
    onJoin: (Ts3Channel) -> Unit,
    onUserChannel: (Ts3Participant) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val results = remember(state.snapshot, query) { searchSession(state.snapshot, query) }
    InputDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discovery_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    {
                        query = it.take(128)
                    },
                    label = { Text(stringResource(R.string.discovery_query)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focus.clearFocus()
                        }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn {
                    item { Text(stringResource(R.string.discovery_channels)) }
                    items(results.channels, key = { "channel-${it.id}" }) { channel ->
                        Text(channel.name)
                        Row {
                            TextButton(onClick = {
                                onInfo(channel.id)
                                onDismiss()
                            }) { Text(stringResource(R.string.channel_info)) }
                            TextButton(onClick = {
                                onJoin(channel)
                                onDismiss()
                            }, enabled = state.switchingChannelId == null && channel.id != state.snapshot.currentChannelId) {
                                Text(stringResource(R.string.action_join))
                            }
                        }
                        HorizontalDivider()
                    }
                    item { Text(stringResource(R.string.discovery_users)) }
                    items(results.participants, key = { "user-${it.id}" }) { participant ->
                        Text(participant.nickname)
                        Text(
                            state.snapshot.channels.firstOrNull { it.id == participant.channelId }?.name
                                ?: stringResource(R.string.channel_unknown),
                        )
                        TextButton(
                            onClick = {
                                onUserChannel(participant)
                                onDismiss()
                            },
                            enabled =
                                participant.uniqueIdentifier.isNotBlank() &&
                                    participant.channelId != state.snapshot.currentChannelId &&
                                    state.switchingChannelId == null,
                        ) {
                            Text(stringResource(R.string.discovery_join_user))
                        }
                        HorizontalDivider()
                    }
                    if (results.channels.isEmpty() && results.participants.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.discovery_empty),
                            )
                        }
                    }
                    item { Text(stringResource(R.string.discovery_limit)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
internal fun ChannelInfoDialog(
    state: TeamSpeakServiceState,
    onRetry: (Int) -> Unit,
    onJoin: (Ts3Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    val request = state.channelInfo ?: return
    val channel = state.snapshot.channels.firstOrNull { it.id == request.channelId }
    val details = request.details
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_info)) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(details?.name?.takeIf { it.isNotBlank() } ?: channel?.name ?: stringResource(R.string.channel_unknown))
                    Text(stringResource(R.string.channel_id_value, request.channelId))
                    if (channel == null) {
                        Text(stringResource(R.string.channel_info_unavailable))
                    } else {
                        Text(stringResource(R.string.channel_population, channel.clientCount))
                        Text(stringResource(R.string.channel_topic_label))
                        Text(details?.topic ?: channel.topic ?: stringResource(R.string.channel_info_unknown))
                        Text(stringResource(R.string.channel_description_label))
                        Text(details?.description ?: stringResource(R.string.channel_info_unknown))
                        Text(stringResource(R.string.channel_limit_label))
                        Text(
                            when {
                                details?.unlimitedClients == true || details == null && channel.unlimitedClients == true ->
                                    stringResource(
                                        R.string.channel_unlimited,
                                    )
                                details?.maxClients != null -> details.maxClients.toString()
                                channel.maxClients != null -> channel.maxClients.toString()
                                else -> stringResource(R.string.channel_info_unknown)
                            },
                        )
                        if (request.loading) Text(stringResource(R.string.channel_info_loading))
                        if (!request.loading && request.outcome?.status != ControlStatus.ACCEPTED) {
                            Text(stringResource(R.string.channel_info_failed))
                            TextButton(onClick = { onRetry(request.channelId) }) { Text(stringResource(R.string.action_retry_control)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        dismissButton = {
            if (channel != null && channel.id != state.snapshot.currentChannelId) {
                TextButton(onClick = {
                    onJoin(channel)
                    onDismiss()
                }, enabled = state.switchingChannelId == null) { Text(stringResource(R.string.action_join)) }
            }
        },
    )
}
