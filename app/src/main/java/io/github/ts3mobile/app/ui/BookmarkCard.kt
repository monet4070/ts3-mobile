package io.github.ts3mobile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.ServerBookmark

@Composable
internal fun BookmarkCard(
    entry: ServerBookmark,
    available: Boolean,
    canConnect: Boolean,
    onEdit: (ServerBookmark) -> Unit,
    onDelete: (ServerBookmark) -> Unit,
    onSelect: (ServerBookmark) -> Unit,
    onConnect: (ServerBookmark) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val connectDescription = stringResource(R.string.bookmark_connect_to, entry.name)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entry.host}:${entry.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = available, modifier = Modifier.size(48.dp)) {
                        AppIcon(UiIcon.More, stringResource(R.string.bookmark_options, entry.name))
                    }
                    DropdownMenu(expanded = showMenu && available, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmark_fill)) },
                            enabled = canConnect,
                            onClick = {
                                showMenu = false
                                onSelect(entry)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmark_edit)) },
                            onClick = {
                                showMenu = false
                                onEdit(entry)
                            },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmark_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete(entry)
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppIcon(UiIcon.User, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        entry.nickname,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { onConnect(entry) },
                    enabled = available && canConnect,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = connectDescription },
                ) {
                    Text(stringResource(R.string.bookmark_connect))
                    Spacer(Modifier.width(8.dp))
                    AppIcon(UiIcon.ArrowRight, null, Modifier.size(16.dp))
                }
            }
        }
    }
}
