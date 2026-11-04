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
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.protocol.boundedText

@Composable
internal fun BookmarkEditor(
    original: ServerBookmark,
    state: BookmarkState,
    onSave: (ServerBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by remember(original.id, original.revision) { mutableStateOf(original) }
    var port by remember(original.id, original.revision) { mutableStateOf(original.port.toString()) }
    var channel by remember(original.id, original.revision) { mutableStateOf(original.defaultChannelId?.toString().orEmpty()) }
    var expanded by remember(original.id) { mutableStateOf(original.defaultChannelId != null || original.rememberPasswords) }
    var submitted by remember(original.id) { mutableStateOf(false) }
    LaunchedEffect(state.entries) {
        if (submitted && state.entries.any { it.id == original.id && it.revision > original.revision }) onDismiss()
    }
    val validated =
        entry.copy(
            port = port.toIntOrNull() ?: 0,
            defaultChannelId = if (channel.isBlank()) null else channel.toIntOrNull() ?: 0,
        )
    val valid = runCatching { validated.validate() }.isSuccess
    val enabled = !state.busy && !state.loading
    InputDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(stringResource(if (original.revision == 0L) R.string.bookmark_add else R.string.bookmark_edit_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.failed) Text(stringResource(R.string.bookmark_failed), color = MaterialTheme.colorScheme.error)
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
                HorizontalDivider(Modifier.padding(top = 4.dp))
                TextButton(onClick = { expanded = !expanded }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bookmark_more_settings), Modifier.weight(1f))
                    AppIcon(UiIcon.ChevronDown, null, Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
                }
                if (expanded) {
                    BookmarkField(R.string.bookmark_default_channel, channel, enabled, KeyboardType.Number) {
                        channel = it.filter(Char::isDigit).take(10)
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
                submitted = true
                onSave(validated)
            }, enabled = valid && enabled) { Text(stringResource(R.string.bookmark_save)) }
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
