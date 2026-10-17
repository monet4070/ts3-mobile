package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.history.ServerHistoryEntry

@Composable
internal fun ServerHistorySection(
    history: List<ServerHistoryEntry>,
    historyError: Boolean,
    enabled: Boolean,
    onSelected: (ServerHistoryEntry) -> Unit,
    onRemoved: (ServerHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(stringResource(R.string.history_title))
            history.forEach { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onSelected(entry) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        AppIcon(UiIcon.History, null)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onRemoved(entry) }, enabled = enabled) {
                        AppIcon(UiIcon.Trash, stringResource(R.string.action_remove_history_entry, entry.label))
                    }
                }
            }
            if (historyError) {
                Text(
                    stringResource(R.string.history_remove_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
