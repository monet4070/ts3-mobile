package io.github.ts3mobile.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkChannelEndpoint
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSnapshot
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSnapshotStore
import io.github.ts3mobile.app.bookmarks.BookmarkConnectionContext
import io.github.ts3mobile.app.bookmarks.bookmarkChannelChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BookmarkSnapshotSave(
    endpoint: BookmarkChannelEndpoint,
    live: BookmarkConnectionContext,
    store: BookmarkChannelSnapshotStore,
    enabled: Boolean,
) {
    val token = live.connection.token
    val currentTarget by rememberUpdatedState(endpoint to token)
    var saving by remember(endpoint, token) { mutableStateOf(false) }
    var result by remember(endpoint, token) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    FeatureListRow(
        UiIcon.Archive,
        stringResource(R.string.bookmark_save_channels_offline),
        enabled = enabled && !saving && live.channels.isNotEmpty(),
        onClick = {
            if (!saving) {
                saving = true
                result = null
                scope.launch {
                    try {
                        val choices = withContext(Dispatchers.Default) { bookmarkChannelChoices(live.channels.filter { it.id > 0 }) }
                        check(currentTarget == (endpoint to token) && choices.isNotEmpty())
                        store.save(BookmarkChannelSnapshot(endpoint, choices))
                        result = R.string.bookmark_saved_channels_success
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        result = R.string.bookmark_saved_channels_failed
                    } finally {
                        saving = false
                    }
                }
            }
        },
        trailing = null,
    )
    if (saving) FeatureNotice(UiIcon.Refresh, stringResource(R.string.bookmark_saved_channels_working))
    result?.let {
        val failed = it == R.string.bookmark_saved_channels_failed
        FeatureNotice(if (failed) UiIcon.Info else UiIcon.Check, stringResource(it), error = failed)
    }
}

@Composable
internal fun BookmarkOfflineChannelPicker(
    endpoint: BookmarkChannelEndpoint,
    store: BookmarkChannelSnapshotStore,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var result by remember(endpoint, store) { mutableStateOf<Result<BookmarkChannelSnapshot?>?>(null) }
    var forgetting by remember(endpoint, store) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(endpoint, store) {
        result = snapshotOperation { store.read(endpoint)?.also { check(it.endpoint == endpoint) } }
    }
    val snapshot = result?.getOrNull()
    if (snapshot == null) {
        val message =
            when {
                result == null -> R.string.bookmark_saved_channels_working
                result?.isFailure == true -> R.string.bookmark_saved_channels_failed
                else -> R.string.bookmark_saved_channels_empty
            }
        InputDialog(
            onDismissRequest = onDismiss,
            title = { FeatureTitle(UiIcon.Archive, stringResource(R.string.bookmark_browse_saved_channels)) },
            text = {
                FeatureNotice(
                    if (result == null) UiIcon.Refresh else UiIcon.Info,
                    stringResource(message),
                    error = result?.isFailure == true,
                )
            },
            confirmButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_close), onDismiss) },
        )
        return
    }
    var removeFailed by remember(endpoint, store) { mutableStateOf(false) }
    BookmarkChannelPickerContent(
        choicesForQuery =
            remember(snapshot) {
                { query -> snapshot.choices.filter { it.path.contains(query.trim(), ignoreCase = true) } }
            },
        onChoose = onChoose,
        onDismiss = { if (!forgetting) onDismiss() },
        title = R.string.bookmark_browse_saved_channels,
        hint = R.string.bookmark_saved_channels_hint,
        enabled = !forgetting,
        error = R.string.bookmark_saved_channels_failed.takeIf { removeFailed },
        dismissButton = {
            FeatureAction(UiIcon.Trash, stringResource(R.string.bookmark_saved_channels_forget), enabled = !forgetting, onClick = {
                if (!forgetting) {
                    forgetting = true
                    scope.launch {
                        try {
                            removeFailed = snapshotOperation { store.remove(endpoint) }.isFailure
                            if (!removeFailed) result = Result.success(null)
                        } finally {
                            forgetting = false
                        }
                    }
                }
            })
        },
    )
}

private suspend fun <T> snapshotOperation(operation: suspend () -> T): Result<T> =
    try {
        Result.success(operation())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
