package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant

@Composable
internal fun SessionSearchDialog(
    state: TeamSpeakServiceState,
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
