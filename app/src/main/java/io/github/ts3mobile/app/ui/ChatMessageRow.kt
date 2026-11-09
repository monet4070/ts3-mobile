package io.github.ts3mobile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
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
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    stringResource(R.string.chat_channel_separator, message.channelId ?: 0),
                    Modifier.padding(horizontal = 12.dp, vertical = 5.dp).testTag("chat_system_" + message.id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    var menu by remember(message.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val outgoing = message.kind == ChatMessageKind.OUTGOING
    val alignment = if (outgoing) Alignment.End else Alignment.Start
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        val bubbleWidth = maxWidth * 0.82f
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!outgoing) {
                Surface(
                    Modifier.padding(end = 8.dp, top = 20.dp).size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) { AppIcon(UiIcon.User, null, Modifier.size(16.dp)) }
                }
            }
            Column(Modifier.widthIn(max = bubbleWidth), horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (outgoing) stringResource(R.string.chat_you) else message.sender ?: stringResource(R.string.chat_unknown_sender),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.testTag("chat_bubble_" + message.id).combinedClickable(onClick = {}, onLongClick = { menu = true }),
                    shape =
                        RoundedCornerShape(
                            topStart = if (outgoing) 16.dp else 4.dp,
                            topEnd = if (outgoing) 4.dp else 16.dp,
                            bottomEnd = 16.dp,
                            bottomStart = 16.dp,
                        ),
                    color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    border = if (outgoing) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (message.type == ChatMessageType.POKE) {
                            Text(
                                stringResource(if (outgoing) R.string.poke_outgoing else R.string.poke_incoming),
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
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (message.channelId == null && !isPrivate) {
                            Text(stringResource(R.string.chat_unknown_channel), style = MaterialTheme.typography.labelSmall)
                        }
                        if (message.truncated) Text(stringResource(R.string.chat_truncated), style = MaterialTheme.typography.labelSmall)
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = {
                                clipboard.setText(AnnotatedString(message.body))
                                menu = false
                            })
                        }
                    }
                }
                Text(
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.receivedAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outgoing) {
                    val status =
                        if (message.type == ChatMessageType.POKE && message.outcome == TextSendOutcome.Unsupported) {
                            R.string.poke_unsupported
                        } else {
                            message.outcome.statusResource()
                        }
                    Text(
                        stringResource(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
