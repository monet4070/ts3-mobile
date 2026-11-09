package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget

sealed interface ChatConversationKey {
    data object Channel : ChatConversationKey

    data class User(val uid: String) : ChatConversationKey {
        override fun toString(): String = "User(<redacted>)"
    }

    data class AttemptUser(val attempt: Long, val clientId: Int, val occupancy: Long) : ChatConversationKey
}

data class ChatConversation(
    val id: Long = 0,
    val key: ChatConversationKey = ChatConversationKey.Channel,
    val title: String? = null,
    val target: TextTarget.Private? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val draftOverflow: Boolean = false,
    val readThrough: Long = 0,
    val privateMessageRevision: Long = 0,
) {
    val unread: Int get() = messages.count { it.kind == ChatMessageKind.INCOMING && it.sequence > readThrough }
    val isPrivate: Boolean get() = key != ChatConversationKey.Channel

    override fun toString(): String = "ChatConversation(id=$id, count=${messages.size}, content=<redacted>)"
}

data class ChatSubmission(val binding: ChatBinding, val conversation: ChatConversationKey, val target: TextTarget, val message: ChatMessage)

internal fun ChatState.ensureConversation(
    key: ChatConversationKey,
    title: String?,
    target: TextTarget.Private?,
    nextId: () -> Long,
    maximum: Int,
): ChatState =
    when {
        conversations.any { it.key == key } -> this
        conversations.size >= maximum -> copy(conversationLimitReached = true)
        else -> copy(conversations = conversations + ChatConversation(nextId(), key, title, target))
    }

internal fun ChatState.withoutAttemptTargets(): ChatState {
    val remaining =
        conversations.filterNot { it.key is ChatConversationKey.AttemptUser }.map { conversation ->
            conversation.copy(
                target = null,
                messages = conversation.messages.map { if (it.isSending) it.copy(outcome = TextSendOutcome.Unknown) else it },
            )
        }
    return copy(
        conversations = remaining,
        selected = selected.takeIf { key -> remaining.any { it.key == key } } ?: ChatConversationKey.Channel,
    )
}
