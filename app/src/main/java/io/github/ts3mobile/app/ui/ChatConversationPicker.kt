package io.github.ts3mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatConversation
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatState

@Composable
internal fun ChatConversationPicker(
    state: ChatState,
    onSelect: (ChatConversationKey) -> Unit,
    onClose: (ChatConversationKey) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf<ChatConversationKey?>(null) }
    closing?.let { captured ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.chat_close_private)) },
            text = { Text(stringResource(R.string.chat_close_confirm)) },
            confirmButton = {
                OutlinedButton(onClick = {
                    onClose(captured)
                    closing = null
                }) { Text(stringResource(R.string.chat_close_private)) }
            },
            dismissButton = { OutlinedButton(onClick = { closing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            val tint = MaterialTheme.colorScheme.primary
            Row(
                modifier =
                    Modifier.testTag("chat_conversation_picker").heightIn(min = 48.dp)
                        .guideAnchor(GuideAnchor.CHAT_CONVERSATIONS)
                        .clip(MaterialTheme.shapes.small)
                        .background(if (expanded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_switch_conversation)) { expanded = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppIcon(UiIcon.Chat, null, Modifier.size(18.dp), tint = tint)
                Text(
                    conversationTitle(state.active, state),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppIcon(UiIcon.ChevronDown, null, Modifier.size(16.dp), tint = tint)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                state.conversations.forEach { conversation ->
                    DropdownMenuItem(
                        text = {
                            val count = if (conversation.unread > 99) "99+" else conversation.unread.toString()
                            val unreadLabel = if (conversation.unread > 0) " ($count)" else ""
                            Text(conversationTitle(conversation, state) + unreadLabel)
                        },
                        onClick = {
                            onSelect(conversation.key)
                            expanded = false
                        },
                        modifier = Modifier.testTag("chat_conversation_${conversation.id}"),
                    )
                }
            }
        }
        if (state.active.isPrivate) {
            IconButton(onClick = {
                if (state.active.messages.isNotEmpty() || state.draft.isNotEmpty()) closing = state.selected else onClose(state.selected)
            }, enabled = state.active.messages.none { it.isSending }) {
                AppIcon(UiIcon.Trash, stringResource(R.string.chat_close_private))
            }
        }
    }
}

@Composable
internal fun conversationTitle(
    conversation: ChatConversation,
    state: ChatState,
): String =
    if (conversation.isPrivate) {
        val name = conversation.title ?: stringResource(R.string.chat_unknown_sender)
        conversation.target?.let { stringResource(R.string.chat_private_client_title, name, it.clientId) }
            ?: stringResource(R.string.chat_private_title, name)
    } else {
        state.binding?.channelId?.let { stringResource(R.string.chat_channel, it) } ?: stringResource(R.string.chat_no_channel)
    }
