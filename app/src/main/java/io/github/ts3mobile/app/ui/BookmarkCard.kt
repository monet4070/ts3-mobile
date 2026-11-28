package io.github.ts3mobile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalDensity
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
    val largeText = LocalDensity.current.fontScale > 1.3f
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(UiIcon.Server, accented = false)
                Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = available, modifier = Modifier.size(48.dp)) {
                        AppIcon(UiIcon.More, stringResource(R.string.bookmark_options, entry.name))
                    }
                    DropdownMenu(expanded = showMenu && available, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmark_fill)) },
                            leadingIcon = { AppIcon(UiIcon.Connect, null) },
                            enabled = canConnect,
                            onClick = {
                                showMenu = false
                                onSelect(entry)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmark_edit)) },
                            leadingIcon = { AppIcon(UiIcon.Edit, null) },
                            onClick = {
                                showMenu = false
                                onEdit(entry)
                            },
                        )
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookmark_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { AppIcon(UiIcon.Trash, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete(entry)
                            },
                        )
                    }
                }
            }
            Text(
                "${entry.host}:${entry.port}",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (largeText) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            val connect: @Composable (Modifier) -> Unit = { modifier ->
                FeatureAction(
                    UiIcon.Connect,
                    stringResource(R.string.bookmark_connect),
                    onClick = { onConnect(entry) },
                    enabled = available && canConnect,
                    modifier = modifier.semantics { contentDescription = connectDescription },
                    style = FeatureActionStyle.PRIMARY,
                )
            }
            if (largeText) {
                BookmarkNickname(entry.nickname)
                connect(Modifier.align(Alignment.End))
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BookmarkNickname(entry.nickname, Modifier.weight(1f).padding(end = 8.dp))
                    connect(Modifier)
                }
            }
        }
    }
}

@Composable
private fun BookmarkNickname(
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AppIcon(UiIcon.User, null, Modifier.size(14.dp), tint = color)
        Text(nickname, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
