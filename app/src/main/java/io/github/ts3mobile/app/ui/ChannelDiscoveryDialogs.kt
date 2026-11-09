package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
            Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    query,
                    {
                        query = it.take(128)
                    },
                    label = { Text(stringResource(R.string.discovery_query)) },
                    leadingIcon = { AppIcon(UiIcon.Search, null) },
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(onSearch = {
                            keyboard?.hide()
                            focus.clearFocus()
                        }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                ) {
                    if (results.channels.isNotEmpty()) {
                        item { SearchGroupTitle(stringResource(R.string.discovery_channels), results.channels.size) }
                    }
                    items(results.channels, key = { "channel-${it.id}" }) { channel ->
                        val current = channel.id == state.snapshot.currentChannelId
                        SearchResultRow(
                            title = channel.name,
                            subtitle = if (channel.hasPassword) stringResource(R.string.discovery_protected) else null,
                            icon = if (channel.hasPassword) UiIcon.Lock else UiIcon.Channel,
                            current = current,
                            enabled = state.switchingChannelId == null && !current,
                            tag = "discovery_channel_" + channel.id,
                            onJoin = {
                                onJoin(channel)
                                onDismiss()
                            },
                        )
                    }
                    if (results.participants.isNotEmpty()) {
                        item { SearchGroupTitle(stringResource(R.string.discovery_users), results.participants.size) }
                    }
                    items(results.participants, key = { "user-${it.id}" }) { participant ->
                        SearchResultRow(
                            title = participant.nickname,
                            subtitle =
                                state.snapshot.channels.firstOrNull { it.id == participant.channelId }?.name
                                    ?: stringResource(R.string.channel_unknown),
                            icon = UiIcon.User,
                            current = participant.channelId == state.snapshot.currentChannelId,
                            enabled =
                                participant.uniqueIdentifier.isNotBlank() &&
                                    participant.channelId != state.snapshot.currentChannelId && state.switchingChannelId == null,
                            tag = "discovery_user_" + participant.id,
                            actionDescription = stringResource(R.string.discovery_join_user),
                            onJoin = {
                                onUserChannel(participant)
                                onDismiss()
                            },
                        )
                    }
                    if (results.channels.isEmpty() && results.participants.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.discovery_empty),
                            )
                        }
                    }
                    if (results.channels.size == 50 || results.participants.size == 50) {
                        item { Text(stringResource(R.string.discovery_limit), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
