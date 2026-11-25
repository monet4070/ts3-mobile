package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.ChannelFileEntry
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ChannelFiles

@Composable
internal fun ChannelFilesDialog(
    files: ChannelFiles,
    onDismiss: () -> Unit,
) {
    val (transfer, chooseDestination) = rememberVerifiedFileTransfer(files)
    var directory by remember(files) { mutableStateOf("/") }
    var refresh by remember(files) { mutableIntStateOf(0) }
    var entries by remember(files) { mutableStateOf<List<ChannelFileEntry>?>(null) }
    var failure by remember(files) { mutableStateOf<ChannelFileFailure?>(null) }

    fun navigate(nextDirectory: String) {
        entries = null
        failure = null
        directory = nextDirectory
    }

    LaunchedEffect(files, directory, refresh) {
        entries = null
        failure = null
        transfer.resetFeedback()
        when (val result = files.list(directory)) {
            is ChannelFileResult.Success ->
                entries = result.value.sortedWith(compareByDescending<ChannelFileEntry> { it.directory }.thenBy { it.name })
            is ChannelFileResult.Failed -> failure = result.reason
        }
    }

    val locked = transfer.locked
    InputDialog(
        onDismissRequest = { if (!transfer.saving) onDismiss() },
        title = { FeatureTitle(UiIcon.Folder, stringResource(R.string.channel_files_probe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureNotice(UiIcon.Folder, directory, Modifier.guideAnchor(GuideAnchor.FILE_LIST))
                if (directory != "/") {
                    FeatureAction(
                        UiIcon.ParentFolder,
                        stringResource(R.string.channel_files_parent),
                        { navigate(directory.substringBeforeLast('/').ifEmpty { "/" }) },
                        enabled = !locked,
                    )
                }
                if (entries == null && failure == null) FeatureNotice(UiIcon.Refresh, stringResource(R.string.channel_files_loading))
                FileTransferFeedback(transfer, failure)
                val listed = entries.orEmpty()
                if (entries?.isEmpty() == true) FeatureNotice(UiIcon.Info, stringResource(R.string.channel_files_empty))
                if (listed.isNotEmpty()) {
                    Panel(Modifier.fillMaxWidth()) {
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            itemsIndexed(listed, key = { _, entry -> entry.name }) { index, entry ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                val folderLabel = stringResource(R.string.channel_files_folder)
                                val guideTarget = if (index == 0) Modifier.guideAnchor(GuideAnchor.FILE_ENTRY) else Modifier
                                FeatureListRow(
                                    if (entry.directory) UiIcon.Folder else UiIcon.FileText,
                                    entry.name,
                                    onClick = {
                                        val remotePath = directory.trimEnd('/') + "/" + entry.name
                                        if (entry.directory) {
                                            navigate(remotePath)
                                        } else {
                                            failure = null
                                            transfer.start(entry.name) { cache -> files.stageDownload(remotePath, cache) }
                                        }
                                    },
                                    modifier =
                                        if (entry.directory) guideTarget.semantics { stateDescription = folderLabel } else guideTarget,
                                    enabled = !locked,
                                    trailing = if (entry.directory) UiIcon.ChevronRight else UiIcon.Download,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                transfer.canSave ->
                    FeatureAction(
                        UiIcon.Download,
                        stringResource(R.string.channel_files_save),
                        chooseDestination,
                        style = FeatureActionStyle.PRIMARY,
                    )
                failure != null && entries == null ->
                    FeatureAction(
                        UiIcon.Refresh,
                        stringResource(R.string.channel_files_reload),
                        onClick = { refresh++ },
                        enabled = failure != ChannelFileFailure.RECONNECT_REQUIRED,
                    )
            }
        },
        dismissButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_close), onDismiss, enabled = !transfer.saving) },
    )
}

@Composable
internal fun FileTransferFeedback(
    transfer: VerifiedFileTransfer,
    listingFailure: ChannelFileFailure? = null,
) {
    val failure = listingFailure ?: transfer.failure
    when {
        failure != null -> FeatureNotice(UiIcon.Info, stringResource(failure.fileMessage()), error = true)
        transfer.downloading -> FeatureNotice(UiIcon.Download, stringResource(R.string.channel_files_downloading))
        transfer.saving -> FeatureNotice(UiIcon.Refresh, stringResource(R.string.channel_files_saving))
        transfer.canSave -> FeatureNotice(UiIcon.Check, stringResource(R.string.channel_files_ready))
        transfer.saved -> FeatureNotice(UiIcon.Check, stringResource(R.string.channel_files_saved))
    }
}
