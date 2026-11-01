package io.github.ts3mobile.app.chat

/** One shared message budget; pending messages cannot be evicted or silently resent. */
internal class ChatRetentionPolicy(private val maxMessages: Int, private val maxBytes: Int) {
    fun append(
        state: ChatState,
        key: ChatConversationKey,
        message: ChatMessage,
    ): ChatState {
        var conversations = state.conversations.map { if (it.key == key) it.copy(messages = it.messages + message) else it }
        var count = conversations.sumOf { it.messages.size }
        var bytes = conversations.sumOf { conversation -> conversation.messages.sumOf { it.retainedBytes } }
        var overflow = state.overflow
        while (count > maxMessages || bytes > maxBytes) {
            val oldest =
                conversations.asSequence().flatMap { it.messages.asSequence() }.filter { !it.isSending }
                    .minByOrNull { it.sequence } ?: break
            conversations = conversations.map { it.copy(messages = it.messages.filterNot { message -> message.id == oldest.id }) }
            count--
            bytes -= oldest.retainedBytes
            overflow = true
        }
        return state.copy(conversations = conversations, overflow = overflow)
    }
}
