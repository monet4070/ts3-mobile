package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatNotificationDestination
import io.github.ts3mobile.app.chat.ChatState

@Composable
internal fun ChatNotificationNavigationEffect(
    request: ChatNotificationDestination?,
    chat: ChatState,
    onSelect: (ChatConversationKey) -> Unit,
    onOpenChat: (showConversations: Boolean) -> Unit,
    onConsumed: (String) -> Unit,
) {
    LaunchedEffect(
        request,
        chat.sessionToken,
        chat.notificationGeneration,
        chat.connected,
        chat.selected,
        chat.conversations.map { it.id },
    ) {
        if (request == null) return@LaunchedEffect
        if (request.matches(chat)) {
            val singleId = request.conversationIds.singleOrNull()
            if (singleId != null && chat.active.id != singleId) {
                onSelect(chat.conversations.single { it.id == singleId }.key)
                return@LaunchedEffect
            }
            onOpenChat(singleId == null)
        }
        onConsumed(request.requestId)
    }
}

/** A summary opens a list without mounting messages or advancing any read receipt. */
@Composable
internal fun ChatNotificationConversationList(
    chat: ChatState,
    onSelect: (Long, ChatConversationKey) -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("chat_notification_conversations").padding(12.dp)) {
        Text(stringResource(R.string.private_message_conversations))
        LazyColumn {
            items(chat.conversations.filter { it.isPrivate }, key = { it.id }) { conversation ->
                TextButton(
                    onClick = { onSelect(conversation.id, conversation.key) },
                    modifier = Modifier.testTag("chat_notification_conversation_${conversation.id}"),
                ) {
                    Text(conversationTitle(conversation, chat))
                }
            }
        }
    }
}
