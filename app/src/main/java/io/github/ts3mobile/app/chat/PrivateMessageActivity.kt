package io.github.ts3mobile.app.chat

/** One revision per accepted private TEXT; bounded by the existing conversation cap. */
internal fun ChatState.withPrivateMessageActivity(key: ChatConversationKey): ChatState {
    val revision = privateMessageRevision + 1
    return copy(
        privateMessageRevision = revision,
        conversations = conversations.map { if (it.key == key) it.copy(privateMessageRevision = revision) else it },
    )
}

internal fun ChatState.invalidateMessageNotifications(): ChatState =
    copy(
        notificationGeneration = notificationGeneration + 1,
        privateMessageRevision = 0,
        conversations = conversations.map { it.copy(privateMessageRevision = 0) },
    )
