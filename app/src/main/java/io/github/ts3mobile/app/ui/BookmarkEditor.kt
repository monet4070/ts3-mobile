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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkChannelEndpoint
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSelection
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSnapshotStore
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSnapshotVault
import io.github.ts3mobile.app.bookmarks.BookmarkConnectionContext
import io.github.ts3mobile.app.bookmarks.BookmarkDraft
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
    snapshotStore: BookmarkChannelSnapshotStore? = null,
    onDismiss: () -> Unit,
) {
    var draft by remember(original.id, original.revision) { mutableStateOf(BookmarkDraft(original, selection = initialSelection)) }
    var expanded by remember(original.id) { mutableStateOf(original.defaultChannelId != null || original.rememberPasswords) }
    var submitted by remember(original.id) { mutableStateOf<ServerBookmark?>(null) }
    var choosingChannel by remember(original.id) { mutableStateOf(false) }
    LaunchedEffect(state.entries, state.busy, state.failed, submitted) {
        val expected = submitted?.forStorage()?.copy(revision = original.revision + 1)
        if (expected != null && !state.busy && !state.failed && state.entries.any { it == expected }) onDismiss()
    }
    val entry = draft.entry
    val validated = draft.toBookmark()
    val valid = runCatching { validated.validate() }.isSuccess
    val enabled = !state.busy && !state.loading
    val matchingContext = context?.takeIf { it.connection.matches(validated) }
    val endpoint = BookmarkChannelEndpoint.parse(entry.host, validated.port)
    var choosingSavedChannel by remember(original.id, endpoint) { mutableStateOf(false) }
    val applicationContext = LocalContext.current.applicationContext
    val savedChannels = remember(applicationContext, snapshotStore) { snapshotStore ?: BookmarkChannelSnapshotVault(applicationContext) }
    val staleContext = draft.selection?.let { context?.accepts(validated, it) != true } ?: false
    val changePort: (String) -> Unit = { draft = draft.withEndpoint(port = it.filter(Char::isDigit).take(5)) }
    val chooseChannel: (Int) -> Unit = { id ->
        matchingContext?.takeIf { live -> live.channels.count { it.id == id } == 1 }?.let { live ->
            draft = draft.withChannel(id.toString(), BookmarkChannelSelection(live.connection.token, id))
            choosingChannel = false
        }
    }
    if (choosingChannel && matchingContext != null) {
        BookmarkChannelPicker(matchingContext.channels, chooseChannel) { choosingChannel = false }
        return
    }
    if (choosingSavedChannel && endpoint != null) {
        BookmarkOfflineChannelPicker(
            endpoint,
            savedChannels,
            onChoose = { id ->
                if (BookmarkChannelEndpoint.parse(draft.entry.host, draft.toBookmark().port) == endpoint) {
                    draft = draft.withChannel(id.toString(), selection = null)
                }
                choosingSavedChannel = false
            },
            onDismiss = { choosingSavedChannel = false },
        )
        return
    }
    InputDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = {
            FeatureTitle(
                UiIcon.Bookmark,
                stringResource(if (original.revision == 0L) R.string.bookmark_add else R.string.bookmark_edit_title),
            )
        },
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
                BookmarkField(R.string.bookmark_name, entry.name, enabled) {
                    draft = draft.copy(entry = draft.entry.copy(name = it.take(80)))
                }
                SectionLabel(stringResource(R.string.bookmark_connection_section))
                BookmarkField(R.string.field_server_address, entry.host, enabled, KeyboardType.Uri) {
                    draft = draft.withEndpoint(host = it.take(255))
                }
                val changeNickname: (String) -> Unit = { draft = draft.copy(entry = draft.entry.copy(nickname = it.take(30))) }
                if (LocalDensity.current.fontScale > 1.3f) {
                    BookmarkField(R.string.field_port, draft.port, enabled, KeyboardType.Number, onChange = changePort)
                    BookmarkField(R.string.field_nickname, entry.nickname, enabled, onChange = changeNickname)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BookmarkField(
                            R.string.field_port,
                            draft.port,
                            enabled,
                            KeyboardType.Number,
                            Modifier.weight(1f),
                            onChange = changePort,
                        )
                        BookmarkField(
                            R.string.field_nickname,
                            entry.nickname,
                            enabled,
                            modifier = Modifier.weight(1.7f),
                            onChange = changeNickname,
                        )
                    }
                }
                if (matchingContext != null) {
                    FeatureAction(
                        UiIcon.Channel,
                        stringResource(R.string.bookmark_choose_channel),
                        { choosingChannel = true },
                        enabled = enabled,
                    )
                    val current = matchingContext.currentChannelId
                    FeatureAction(
                        UiIcon.Current,
                        stringResource(R.string.bookmark_use_current_channel),
                        { current?.let(chooseChannel) },
                        enabled = enabled && current != null,
                    )
                    val selected = validated.defaultChannelId
                    val path =
                        remember(matchingContext.channels, selected) {
                            bookmarkChannelChoices(matchingContext.channels, "(#$selected)").firstOrNull { it.id == selected }?.path
                        }
                    if (path != null) Text(path, style = MaterialTheme.typography.bodySmall)
                }
                if (draft.channel.isNotBlank() || draft.selection != null) {
                    FeatureAction(
                        UiIcon.Close,
                        stringResource(R.string.bookmark_clear_channel),
                        { draft = draft.withChannel("", selection = null) },
                        enabled = enabled,
                    )
                }
                if (draft.clearedPasswords) {
                    Text(
                        stringResource(R.string.bookmark_passwords_cleared),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(top = 4.dp))
                OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bookmark_more_settings), Modifier.weight(1f))
                    AppIcon(UiIcon.ChevronDown, null, Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
                }
                if (expanded) {
                    BookmarkField(R.string.bookmark_default_channel, draft.channel, enabled, KeyboardType.Number) {
                        draft = draft.withChannel(it.filter(Char::isDigit).take(10))
                    }
                    if (endpoint != null) {
                        Panel(Modifier.fillMaxWidth()) {
                            if (matchingContext?.connection?.let { BookmarkChannelEndpoint.parse(it.host, it.port) } == endpoint) {
                                BookmarkSnapshotSave(endpoint, checkNotNull(matchingContext), savedChannels, enabled)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            FeatureListRow(
                                UiIcon.Archive,
                                stringResource(R.string.bookmark_browse_saved_channels),
                                { choosingSavedChannel = true },
                                enabled = enabled,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = entry.rememberPasswords,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = { draft = draft.withPasswordStorage(it) },
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
                            draft = draft.copy(entry = draft.entry.copy(password = it.boundedText(1024)))
                        }
                        BookmarkField(R.string.bookmark_channel_password, entry.channelPassword, enabled, password = true) {
                            draft = draft.copy(entry = draft.entry.copy(channelPassword = it.boundedText(1024)))
                        }
                    }
                }
            }
        },
        confirmButton = {
            FeatureAction(
                UiIcon.Check,
                stringResource(R.string.bookmark_save),
                onClick = {
                    submitted = validated
                    val captured = draft.selection
                    if (captured == null) onSave(validated) else onSaveFromConnection(validated, captured)
                },
                enabled = valid && enabled && !staleContext,
                style = FeatureActionStyle.PRIMARY,
            )
        },
        dismissButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_cancel), onDismiss, enabled = !state.busy) },
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
