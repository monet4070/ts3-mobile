package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkCodec
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark

@Composable
internal fun BookmarkListDialog(
    state: BookmarkState,
    canConnect: Boolean,
    onAdd: () -> Unit,
    onEdit: (ServerBookmark) -> Unit,
    onDelete: (ServerBookmark) -> Unit,
    onSelect: (ServerBookmark) -> Unit,
    onConnect: (ServerBookmark) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val available = !state.loading && !state.busy
    InputDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.bookmarks_title))
                if (state.entries.isNotEmpty()) {
                    Text(
                        stringResource(R.string.bookmark_count, state.entries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.failed) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(stringResource(R.string.bookmark_failed), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = onReload, enabled = available) { Text(stringResource(R.string.bookmark_reload)) }
                        }
                    }
                }
                if (state.loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bookmark_loading))
                    }
                }
                if (!canConnect && state.entries.isNotEmpty()) {
                    Text(
                        stringResource(R.string.bookmark_disconnect_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.entries.isEmpty() && !state.loading && !state.failed) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconTile(UiIcon.Bookmark, accented = false)
                        Text(stringResource(R.string.bookmark_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.entries.forEach { entry ->
                    key(entry.id) {
                        BookmarkCard(entry, available, canConnect, onEdit, onDelete, onSelect, onConnect)
                    }
                }
                if (state.entries.size >= BookmarkCodec.MAX_ENTRIES) {
                    Text(
                        stringResource(R.string.bookmark_limit, BookmarkCodec.MAX_ENTRIES),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onAdd, enabled = available && state.entries.size < BookmarkCodec.MAX_ENTRIES) {
                AppIcon(UiIcon.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.bookmark_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
