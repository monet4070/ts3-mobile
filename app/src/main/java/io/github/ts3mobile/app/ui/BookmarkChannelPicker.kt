package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.github.ts3mobile.app.bookmarks.BookmarkChannelChoice
import io.github.ts3mobile.app.bookmarks.bookmarkChannelChoices
import io.github.ts3mobile.protocol.Ts3Channel

@Composable
internal fun BookmarkChannelPicker(
    channels: List<Ts3Channel>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BookmarkChannelPickerContent(
        choicesForQuery = remember(channels) { { query -> bookmarkChannelChoices(channels, query) } },
        onChoose = onChoose,
        onDismiss = onDismiss,
    )
}

@Composable
internal fun BookmarkChannelPickerContent(
    choicesForQuery: (String) -> List<BookmarkChannelChoice>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: Int = R.string.bookmark_choose_channel,
    hint: Int = R.string.bookmark_channel_result_limit,
    enabled: Boolean = true,
    error: Int? = null,
    dismissButton: @Composable () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val choices = remember(choicesForQuery, query) { choicesForQuery(query) }
    InputDialog(
        onDismissRequest = onDismiss,
        title = {
            FeatureTitle(
                if (title == R.string.bookmark_browse_saved_channels) UiIcon.Archive else UiIcon.Channel,
                stringResource(title),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    query,
                    { query = it.take(128) },
                    label = { Text(stringResource(R.string.bookmark_channel_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    leadingIcon = { AppIcon(UiIcon.Search, null) },
                    singleLine = true,
                )
                FeatureNotice(UiIcon.Info, stringResource(hint))
                error?.let { FeatureNotice(UiIcon.Info, stringResource(it), error = true) }
                if (choices.isEmpty()) {
                    FeatureNotice(UiIcon.Search, stringResource(R.string.bookmark_channel_empty))
                } else {
                    Panel(Modifier.fillMaxWidth()) {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            itemsIndexed(choices, key = { _, choice -> choice.id }) { index, choice ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                FeatureListRow(
                                    UiIcon.Channel,
                                    choice.path,
                                    { onChoose(choice.id) },
                                    Modifier.testTag("bookmark_channel_${choice.id}"),
                                    enabled = enabled,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_close), onDismiss, enabled = enabled) },
        dismissButton = dismissButton,
    )
}
