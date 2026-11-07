package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSelection
import io.github.ts3mobile.app.bookmarks.BookmarkConnectionContext
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.bookmarks.bookmarkChannelChoices
import io.github.ts3mobile.protocol.boundedText

@Composable
internal fun BookmarkEditor(
    original: ServerBookmark,
    state: BookmarkState,
    onSave: (ServerBookmark) -> Unit,
    context: BookmarkConnectionContext? = null,
    initialSelection: BookmarkChannelSelection? = null,
    onSaveFromConnection: (ServerBookmark, BookmarkChannelSelection) -> Unit = { entry, _ -> onSave(entry) },
    onDismiss: () -> Unit,
) {
    var entry by remember(original.id, original.revision) { mutableStateOf(original) }
    var port by remember(original.id, original.revision) { mutableStateOf(original.port.toString()) }
    var channel by remember(original.id, original.revision) { mutableStateOf(original.defaultChannelId?.toString().orEmpty()) }
    var expanded by remember(original.id) { mutableStateOf(original.defaultChannelId != null || original.rememberPasswords) }
    var submitted by remember(original.id) { mutableStateOf<ServerBookmark?>(null) }
    var selection by remember(original.id) { mutableStateOf(initialSelection) }
    var choosingChannel by remember(original.id) { mutableStateOf(false) }
    LaunchedEffect(state.entries, state.busy, state.failed, submitted) {
        val expected = submitted?.forStorage()?.copy(revision = original.revision + 1)
        if (expected != null && !state.busy && !state.failed && state.entries.any { it == expected }) onDismiss()
    }
    val validated =
        entry.copy(
            port = port.toIntOrNull() ?: 0,
            defaultChannelId = if (channel.isBlank()) null else channel.toIntOrNull() ?: 0,
        )
    val valid = runCatching { validated.validate() }.isSuccess
    val enabled = !state.busy && !state.loading
    val matchingContext = context?.takeIf { it.connection.matches(validated) }
    val staleContext = selection?.let { context?.accepts(validated, it) != true } ?: false
    val chooseChannel: (Int) -> Unit = { id ->
        matchingContext?.takeIf { live -> live.channels.count { it.id == id } == 1 }?.let { live ->
            if (channel != id.toString()) entry = entry.copy(channelPassword = "")
            channel = id.toString()
            selection = BookmarkChannelSelection(live.connection.token, id)
            choosingChannel = false
        }
    }
    if (choosingChannel && matchingContext != null) {
        BookmarkChannelPicker(matchingContext.channels, chooseChannel) { choosingChannel = false }
        return
    }
    InputDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(if (original.revision == 0L) R.string.bookmark_add else R.string.bookmark_edit_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.failed) {
                    Text(
                        stringResource(if (state.contextChanged) R.string.bookmark_context_changed else R.string.bookmark_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (staleContext) Text(stringResource(R.string.bookmark_context_changed), color = MaterialTheme.colorScheme.error)
                BookmarkField(R.string.bookmark_name, entry.name, enabled) { entry = entry.copy(name = it.take(80)) }
                SectionLabel(stringResource(R.string.bookmark_connection_section))
                BookmarkField(R.string.field_server_address, entry.host, enabled, KeyboardType.Uri) {
                    entry = entry.copy(host = it.take(255))
                }
                if (LocalDensity.current.fontScale > 1.3f) {
                    BookmarkField(R.string.field_port, port, enabled, KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) }
                    BookmarkField(R.string.field_nickname, entry.nickname, enabled) { entry = entry.copy(nickname = it.take(30)) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BookmarkField(R.string.field_port, port, enabled, KeyboardType.Number, Modifier.weight(1f)) {
                            port = it.filter(Char::isDigit).take(5)
                        }
                        BookmarkField(R.string.field_nickname, entry.nickname, enabled, modifier = Modifier.weight(1.7f)) {
                            entry = entry.copy(nickname = it.take(30))
                        }
                    }
                }
                if (matchingContext != null) {
                    TextButton(onClick = { choosingChannel = true }, enabled = enabled) {
                        Text(stringResource(R.string.bookmark_choose_channel))
                    }
                    val current = matchingContext.currentChannelId
                    TextButton(onClick = { current?.let(chooseChannel) }, enabled = enabled && current != null) {
                        Text(stringResource(R.string.bookmark_use_current_channel))
                    }
                    val selected = validated.defaultChannelId
                    val path =
                        remember(matchingContext.channels, selected) {
                            bookmarkChannelChoices(matchingContext.channels, "(#$selected)").firstOrNull { it.id == selected }?.path
                        }
                    if (path != null) Text(path, style = MaterialTheme.typography.bodySmall)
                }
                if (channel.isNotBlank() || selection != null) {
                    TextButton(onClick = {
                        channel = ""
                        selection = null
                        entry = entry.copy(channelPassword = "")
                    }, enabled = enabled) {
                        Text(stringResource(R.string.bookmark_clear_channel))
                    }
                }
                HorizontalDivider(Modifier.padding(top = 4.dp))
                TextButton(onClick = { expanded = !expanded }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bookmark_more_settings), Modifier.weight(1f))
                    AppIcon(UiIcon.ChevronDown, null, Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
                }
                if (expanded) {
                    BookmarkField(R.string.bookmark_default_channel, channel, enabled, KeyboardType.Number) {
                        channel = it.filter(Char::isDigit).take(10)
                        selection = null
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = entry.rememberPasswords,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = {
                                entry =
                                    entry.copy(
                                        rememberPasswords = it,
                                        password = if (it) entry.password else "",
                                        channelPassword = if (it) entry.channelPassword else "",
                                    )
                            },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(entry.rememberPasswords, onCheckedChange = null, enabled = enabled)
                        Text(stringResource(R.string.bookmark_remember_passwords))
                    }
                    if (entry.rememberPasswords) {
                        Text(
                            stringResource(R.string.bookmark_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BookmarkField(R.string.field_server_password, entry.password, enabled, password = true) {
                            entry = entry.copy(password = it.boundedText(1024))
                        }
                        BookmarkField(R.string.bookmark_channel_password, entry.channelPassword, enabled, password = true) {
                            entry = entry.copy(channelPassword = it.boundedText(1024))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                submitted = validated
                val captured = selection
                if (captured == null) onSave(validated) else onSaveFromConnection(validated, captured)
            }, enabled = valid && enabled && !staleContext) { Text(stringResource(R.string.bookmark_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BookmarkField(
    label: Int,
    value: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = connectionFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = ImeAction.Done),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
