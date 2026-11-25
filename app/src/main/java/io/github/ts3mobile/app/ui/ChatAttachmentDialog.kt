package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.AttachmentDownload
import io.github.ts3mobile.protocol.ChannelAttachment
import io.github.ts3mobile.protocol.VirtualServerUid

internal typealias OpenChatAttachment = (String, Long, ChannelAttachment) -> AttachmentDownload?

internal class ChatAttachmentActions(
    val uid: VirtualServerUid,
    val channels: Set<Int>,
    private val onOpen: (ChannelAttachment) -> Unit,
) {
    fun accepts(file: ChannelAttachment): Boolean = file.serverUid == uid && file.channelId in channels

    fun open(file: ChannelAttachment) {
        if (accepts(file)) onOpen(file)
    }
}

private data class AttachmentSelection(val file: ChannelAttachment, val download: AttachmentDownload?)

@Composable
internal fun rememberChatAttachmentActions(
    state: TeamSpeakServiceState,
    binding: ChatBinding?,
    open: OpenChatAttachment?,
): ChatAttachmentActions? {
    val token = state.bookmarkConnection?.token ?: return null
    val uid = state.snapshot.serverUid ?: return null
    val attempt = binding?.attempt ?: return null
    if (open == null) return null
    val channels = remember(state.snapshot.channels) { state.snapshot.channels.associateBy { it.id } }
    var selection by remember(token, uid, attempt) { mutableStateOf<AttachmentSelection?>(null) }
    selection?.let { selected ->
        val channel = channels[selected.file.channelId]
        if (channel != null) {
            ChatAttachmentDialog(selected.file, channel.name, selected.download) { selection = null }
        } else {
            LaunchedEffect(selected) { if (selection == selected) selection = null }
        }
    }
    return ChatAttachmentActions(uid, channels.keys) { file -> selection = AttachmentSelection(file, open(token, attempt, file)) }
}

@Composable
internal fun ChatAttachmentDialog(
    file: ChannelAttachment,
    channelName: String,
    download: AttachmentDownload?,
    onDismiss: () -> Unit,
) {
    val (transfer, chooseDestination) = rememberVerifiedFileTransfer(file)
    InputDialog(
        onDismissRequest = { if (!transfer.saving) onDismiss() },
        title = { FeatureTitle(UiIcon.FileText, stringResource(R.string.chat_attachment_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(file.fileName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.chat_attachment_channel, channelName, file.directory),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (download == null) FeatureNotice(UiIcon.Info, stringResource(R.string.channel_files_session_changed), error = true)
                FileTransferFeedback(transfer)
            }
        },
        confirmButton = {
            if (transfer.canSave) {
                FeatureAction(
                    UiIcon.Download,
                    stringResource(R.string.channel_files_save),
                    chooseDestination,
                    style = FeatureActionStyle.PRIMARY,
                )
            } else if (download != null && !transfer.locked && !transfer.saved) {
                FeatureAction(
                    UiIcon.Download,
                    stringResource(R.string.chat_attachment_download),
                    { transfer.start(file.fileName, download::stage) },
                    style = FeatureActionStyle.PRIMARY,
                )
            }
        },
        dismissButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_close), onDismiss, enabled = !transfer.saving) },
    )
}
