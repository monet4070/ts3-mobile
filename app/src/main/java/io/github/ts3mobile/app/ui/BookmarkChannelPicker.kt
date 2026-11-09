package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.bookmarkChannelChoices
import io.github.ts3mobile.protocol.Ts3Channel

@Composable
internal fun BookmarkChannelPicker(
    channels: List<Ts3Channel>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val choices = remember(channels, query) { bookmarkChannelChoices(channels, query) }
    InputDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_choose_channel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it.take(128) },
                    label = { Text(stringResource(R.string.bookmark_channel_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(stringResource(R.string.bookmark_channel_result_limit), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(choices, key = { it.id }) { choice ->
                        TextButton(
                            onClick = { onChoose(choice.id) },
                            modifier = Modifier.fillMaxWidth().testTag("bookmark_channel_${choice.id}"),
                        ) { Text(choice.path, Modifier.fillMaxWidth()) }
                    }
                    if (choices.isEmpty()) item { Text(stringResource(R.string.bookmark_channel_empty)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
