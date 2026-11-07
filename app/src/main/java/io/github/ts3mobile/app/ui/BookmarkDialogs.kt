package io.github.ts3mobile.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSelection
import io.github.ts3mobile.app.bookmarks.BookmarkConnectionContext
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
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
    connection: BookmarkConnectionContext? = null,
    onSaveFromConnection: (ServerBookmark, BookmarkChannelSelection) -> Unit = { entry, _ -> onSave(entry) },
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<ServerBookmark?>(null) }
    var deleting by remember { mutableStateOf<ServerBookmark?>(null) }
    var initialSelection by remember { mutableStateOf<BookmarkChannelSelection?>(null) }
    editing?.let { original ->
        BookmarkEditor(original, state, onSave, connection, initialSelection, onSaveFromConnection) { editing = null }
        return
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = { Text(stringResource(R.string.bookmark_delete_hint, entry.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        onRemove(entry)
                        deleting = null
                    },
                    enabled = !state.busy && !state.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.bookmark_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }
    BookmarkListDialog(
        state = state,
        canConnect = canConnect,
        onAdd = {
            initialSelection = null
            editing =
                ServerBookmark(
                    UUID.randomUUID().toString(),
                    name = "",
                    host = form.host,
                    port = form.port.toIntOrNull() ?: 9987,
                    nickname = form.nickname,
                )
        },
        onEdit = {
            initialSelection = null
            editing = it
        },
        onDelete = { deleting = it },
        onSelect = {
            onSelect(it)
            onDismiss()
        },
        onConnect = {
            onConnect(it)
            onDismiss()
        },
        onReload = onReload,
        onDismiss = onDismiss,
        onSaveCurrent =
            connection?.let { active ->
                {
                    val id = active.currentChannelId?.takeIf { channel -> active.channels.count { it.id == channel } == 1 }
                    initialSelection = BookmarkChannelSelection(active.connection.token, id)
                    editing =
                        ServerBookmark(
                            UUID.randomUUID().toString(), name = "", host = active.connection.host,
                            port = active.connection.port, nickname = active.connection.nickname, defaultChannelId = id,
                        )
                }
            },
    )
}
