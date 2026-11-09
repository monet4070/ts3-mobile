package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.chat.ChatNotificationDestination
import io.github.ts3mobile.app.chat.ChatState
import java.util.UUID

/** Service-owned capabilities, bounded in count and time. Never persisted or logged. */
internal class ChatNotificationRegistry(
    private val clockMs: () -> Long,
    private val ttlMs: Long = 5 * 60_000,
    private val capacity: Int = 16,
) {
    private class Entry(val destination: ChatNotificationDestination, val expiresAt: Long)

    private val entries = linkedMapOf<String, Entry>()

    init {
        require(ttlMs > 0 && capacity > 0)
    }

    @Synchronized fun issue(
        state: ChatState,
        conversationIds: Set<Long>,
    ): String? {
        prune(state)
        val session = state.sessionToken ?: return null
        val token = UUID.randomUUID().toString()
        val destination = ChatNotificationDestination(token, session, state.notificationGeneration, conversationIds.toSet())
        if (!destination.matches(state)) return null
        while (entries.size >= capacity) entries.remove(entries.keys.first())
        entries[token] = Entry(destination, clockMs() + ttlMs)
        return token
    }

    @Synchronized fun consume(
        token: String,
        state: ChatState,
    ): ChatNotificationDestination? {
        prune(state)
        return entries.remove(token)?.destination
    }

    @Synchronized fun clear() = entries.clear()

    private fun prune(state: ChatState) {
        val now = clockMs()
        entries.entries.removeAll { (_, entry) -> entry.expiresAt <= now || !entry.destination.matches(state) }
    }
}
