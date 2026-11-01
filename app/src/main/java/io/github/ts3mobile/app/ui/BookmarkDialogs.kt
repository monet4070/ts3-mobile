package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.protocol.boundedText
import java.util.UUID

@Composable
internal fun BookmarkDialogs(
    state: BookmarkState,
    form: ConnectionFormState,
    canConnect: Boolean,
    onSave: (ServerBookmark) -> Unit,
    onRemove: (ServerBookmark) -> Unit,
    onSelect: (ServerBookmark) -> Unit,
    onConnect: (ServerBookmark) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<ServerBookmark?>(null) }
    var deleting by remember { mutableStateOf<ServerBookmark?>(null) }
    if (editing != null) {
        BookmarkEditor(editing!!, state, onSave) { editing = null }
        return
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = { Text(stringResource(R.string.bookmark_delete_hint, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(entry)
                    deleting = null
                }, enabled = !state.busy) { Text(stringResource(R.string.bookmark_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.failed) {
                    Text(stringResource(R.string.bookmark_failed))
                    TextButton(onClick = onReload, enabled = !state.busy) { Text(stringResource(R.string.bookmark_reload)) }
                }
                if (state.loading) Text(stringResource(R.string.bookmark_loading))
                if (!canConnect) Text(stringResource(R.string.bookmark_disconnect_first))
                TextButton(onClick = {
                    editing =
                        ServerBookmark(
                            UUID.randomUUID().toString(),
                            name = "",
                            host = form.host,
                            port = form.port.toIntOrNull() ?: 9987,
                            nickname = form.nickname,
                        )
                }, enabled = !state.loading && !state.busy && state.entries.size < 50) { Text(stringResource(R.string.bookmark_add)) }
                if (state.entries.isEmpty() && !state.loading) Text(stringResource(R.string.bookmark_empty))
                state.entries.forEach { entry ->
                    Text(entry.name)
                    Text("${entry.host}:${entry.port} · ${entry.nickname}")
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            onSelect(entry)
                            onDismiss()
                        }, enabled = canConnect && !state.busy) { Text(stringResource(R.string.bookmark_fill)) }
                        TextButton(onClick = {
                            onConnect(entry)
                            onDismiss()
                        }, enabled = canConnect && !state.busy) { Text(stringResource(R.string.bookmark_connect)) }
                    }
                    Row {
                        TextButton(onClick = { editing = entry }, enabled = !state.busy) { Text(stringResource(R.string.bookmark_edit)) }
                        TextButton(onClick = { deleting = entry }, enabled = !state.busy) { Text(stringResource(R.string.bookmark_delete)) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun BookmarkEditor(
    original: ServerBookmark,
    state: BookmarkState,
    onSave: (ServerBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by remember(original.id, original.revision) { mutableStateOf(original) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var channel by remember { mutableStateOf(original.defaultChannelId?.toString().orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(state.entries) {
        if (submitted && state.entries.any { it.id == original.id && it.revision > original.revision }) onDismiss()
    }
    val validated =
        entry.copy(
            port = port.toIntOrNull() ?: 0,
            defaultChannelId = if (channel.isBlank()) null else channel.toIntOrNull() ?: 0,
        )
    val valid = runCatching { validated.validate() }.isSuccess
    InputDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(R.string.bookmark_edit_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.failed) Text(stringResource(R.string.bookmark_failed))
                BookmarkField(R.string.bookmark_name, entry.name) { entry = entry.copy(name = it.take(80)) }
                BookmarkField(R.string.field_server_address, entry.host) { entry = entry.copy(host = it.take(255)) }
                BookmarkField(R.string.field_port, port) { port = it.filter(Char::isDigit).take(5) }
                BookmarkField(R.string.field_nickname, entry.nickname) { entry = entry.copy(nickname = it.take(30)) }
                BookmarkField(R.string.bookmark_default_channel, channel) { channel = it.filter(Char::isDigit).take(10) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(entry.rememberPasswords, {
                        entry =
                            entry.copy(
                                rememberPasswords = it,
                                password = if (it) entry.password else "",
                                channelPassword = if (it) entry.channelPassword else "",
                            )
                    })
                    Text(stringResource(R.string.bookmark_remember_passwords))
                }
                if (entry.rememberPasswords) {
                    OutlinedTextField(entry.password, {
                        entry = entry.copy(password = it.boundedText(1024))
                    }, label = {
                        Text(stringResource(R.string.field_server_password))
                    }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(entry.channelPassword, {
                        entry = entry.copy(channelPassword = it.boundedText(1024))
                    }, label = {
                        Text(stringResource(R.string.bookmark_channel_password))
                    }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                Text(stringResource(R.string.bookmark_password_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                submitted = true
                onSave(validated)
            }, enabled = valid && !state.busy && !state.loading) { Text(stringResource(R.string.bookmark_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BookmarkField(
    label: Int,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
