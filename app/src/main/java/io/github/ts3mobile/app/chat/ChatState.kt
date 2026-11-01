package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.MAX_TEXT_INPUT_BYTES
import io.github.ts3mobile.protocol.TextTarget

data class ChatState(
    val scope: Long? = null,
    val binding: ChatBinding? = null,
    val connected: Boolean = false,
    val established: Boolean = false,
    val ownClientId: Int? = null,
    val switching: Boolean = false,
    val conversations: List<ChatConversation> = listOf(ChatConversation()),
    val selected: ChatConversationKey = ChatConversationKey.Channel,
    val overflow: Boolean = false,
    val invalidEvent: Boolean = false,
    val conversationLimitReached: Boolean = false,
    val pokeNoticeId: Long? = null,
) {
    val active: ChatConversation get() = conversations.firstOrNull { it.key == selected } ?: conversations.first()
    val messages: List<ChatMessage> get() = active.messages
    val draft: String get() = active.draft
    val draftOverflow: Boolean get() = active.draftOverflow
    val readThrough: Long get() = active.readThrough
    val sending: Boolean get() = conversations.any { conversation -> conversation.messages.any { it.isSending } }
    val draftBytes: Int get() = draft.toByteArray(Charsets.UTF_8).size
    val target: TextTarget? get() = if (active.isPrivate) active.target else binding?.channelId?.let { TextTarget.Channel(it) }
    val canSend: Boolean get() =
        connected && (!switching || active.isPrivate) && target != null && !sending &&
            draft.isNotBlank() && !draftOverflow && draftBytes <= MAX_TEXT_INPUT_BYTES
    val unread: Int get() = active.unread
    val totalUnread: Int get() = conversations.sumOf { it.unread }
    val pokeNotice: ChatMessage? get() =
        conversations.asSequence().flatMap { it.messages.asSequence() }.firstOrNull { it.id == pokeNoticeId }

    override fun toString(): String = "ChatState(active=${scope != null}, conversations=${conversations.size}, content=<redacted>)"
}
