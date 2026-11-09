package io.github.ts3mobile.app.chat

/** Memory-only navigation request. No message, UID, nickname or endpoint is saved. */
data class ChatNotificationDestination(
    val requestId: String,
    val sessionToken: String,
    val generation: Long,
    val conversationIds: Set<Long>,
) {
    fun matches(state: ChatState): Boolean =
        state.connected && state.sessionToken == sessionToken && state.notificationGeneration == generation &&
            conversationIds.isNotEmpty() &&
            conversationIds.all { id -> state.conversations.any { it.id == id && it.isPrivate } }

    override fun toString(): String = "ChatNotificationDestination(conversations=${conversationIds.size}, token=<redacted>)"
}
