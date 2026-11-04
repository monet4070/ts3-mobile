package io.github.ts3mobile.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatMessage
import io.github.ts3mobile.app.chat.ChatMessageKind
import io.github.ts3mobile.app.chat.ChatMessageType
import io.github.ts3mobile.protocol.TextNotSentReason
import io.github.ts3mobile.protocol.TextRejection
import io.github.ts3mobile.protocol.TextSendOutcome
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatMessageRow(
    message: ChatMessage,
    isPrivate: Boolean = false,
) {
    if (message.kind == ChatMessageKind.CHANNEL) {
        Text(
            stringResource(R.string.chat_channel_separator, message.channelId ?: 0),
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        return
    }
    var menu by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                .combinedClickable(onClick = {}, onLongClick = { menu = true }),
        shape = MaterialTheme.shapes.small,
        color =
            if (message.kind == ChatMessageKind.OUTGOING) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                (
                    if (message.kind == ChatMessageKind.OUTGOING) {
                        stringResource(R.string.chat_you)
                    } else {
                        message.sender ?: stringResource(R.string.chat_unknown_sender)
                    }
                ) + " · " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.receivedAtMs)),
                style = MaterialTheme.typography.labelMedium,
            )
            if (message.type == ChatMessageType.POKE) {
                Text(
                    stringResource(if (message.kind == ChatMessageKind.OUTGOING) R.string.poke_outgoing else R.string.poke_incoming),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                if (message.type == ChatMessageType.POKE && message.body.isEmpty()) {
                    stringResource(
                        R.string.poke_empty_reason,
                    )
                } else {
                    message.body
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (message.channelId == null && !isPrivate) {
                Text(
                    stringResource(R.string.chat_unknown_channel),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (message.truncated) Text(stringResource(R.string.chat_truncated), style = MaterialTheme.typography.labelSmall)
            if (message.kind == ChatMessageKind.OUTGOING) {
                val status =
                    if (message.type == ChatMessageType.POKE && message.outcome == TextSendOutcome.Unsupported) {
                        R.string.poke_unsupported
                    } else {
                        message.outcome.statusResource()
                    }
                Text(stringResource(status), style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = {
                    clipboard.setText(AnnotatedString(message.body))
                    menu = false
                })
            }
        }
    }
}

private fun TextSendOutcome?.statusResource(): Int =
    when (this) {
        null -> R.string.chat_sending
        TextSendOutcome.Accepted -> R.string.chat_accepted
        TextSendOutcome.Unknown -> R.string.chat_unknown
        TextSendOutcome.Unsupported -> R.string.chat_unsupported
        is TextSendOutcome.Rejected ->
            when (reason) {
                TextRejection.PERMISSION -> R.string.chat_permission
                TextRejection.INVALID_INPUT -> R.string.chat_invalid
                TextRejection.SERVER -> R.string.chat_rejected
            }
        is TextSendOutcome.NotSent ->
            when (reason) {
                TextNotSentReason.INVALID_INPUT -> R.string.chat_invalid
                TextNotSentReason.BUSY -> R.string.chat_busy
                TextNotSentReason.NOT_CONNECTED, TextNotSentReason.CONTEXT_CHANGED -> R.string.chat_not_sent
            }
    }
