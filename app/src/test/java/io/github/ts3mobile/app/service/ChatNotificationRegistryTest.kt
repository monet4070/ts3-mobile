package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatNotificationRegistryTest {
    private fun store(uid: String? = "fixture-uid") =
        ChatStore().apply {
            begin(1)
            bind(1, 1, ChatBinding(1, 1, 10, 1))
            receive(1, 1, Ts3TextEvent(TextTargetMode.PRIVATE, 1, 2, uid, "Fixture", "Private body"))
        }

    private fun ChatNotificationRegistry.issue(store: ChatStore) =
        checkNotNull(issue(store.state.value, setOf(store.state.value.conversations.last().id)))

    @Test fun tokenIsOpaqueUniqueAndConsumedOnce() {
        val store = store()
        val registry = ChatNotificationRegistry({ 0 })
        val first = registry.issue(store)
        val second = registry.issue(store)
        assertEquals(36, first.length)
        assertNotEquals(first, second)
        val readThrough = store.state.value.conversations.last().readThrough
        assertNotNull(registry.consume(first, store.state.value))
        assertNull(registry.consume(first, store.state.value))
        assertEquals(readThrough, store.state.value.conversations.last().readThrough)
    }

    @Test fun ttlAndCapacityEvictOldCapabilities() {
        var now = 0L
        val store = store()
        val registry = ChatNotificationRegistry({ now }, ttlMs = 100, capacity = 2)
        val first = registry.issue(store)
        val second = registry.issue(store)
        val third = registry.issue(store)
        assertNull(registry.consume(first, store.state.value))
        assertNotNull(registry.consume(second, store.state.value))
        now = 100
        assertNull(registry.consume(third, store.state.value))
    }

    @Test fun sameNumericScopeAndConversationIdInReplacementServiceCannotMatch() {
        val store = store()
        val replacement = store()
        assertEquals(store.state.value.conversations.last().id, replacement.state.value.conversations.last().id)
        val registry = ChatNotificationRegistry({ 0 })
        val token = registry.issue(store)
        assertNull(registry.consume(token, replacement.state.value))
    }

    @Test fun closeReopenAndReconnectWithSameUidInvalidateOldCapabilities() {
        val store = store()
        val registry = ChatNotificationRegistry({ 0 })
        val token = registry.issue(store)
        store.close(ChatConversationKey.User("fixture-uid"))
        store.receive(1, 1, Ts3TextEvent(TextTargetMode.PRIVATE, 1, 2, "fixture-uid", "Fixture", "New"))
        assertNull(registry.consume(token, store.state.value))
        val reconnectToken = registry.issue(store)
        store.pause(1, 1)
        store.bind(1, 2, ChatBinding(1, 2, 10, 1))
        assertNull(registry.consume(reconnectToken, store.state.value))
    }

    @Test fun noUidTemporaryConversationCannotFollowClientIdReuse() {
        val store = store(uid = null)
        val registry = ChatNotificationRegistry({ 0 })
        val token = registry.issue(store)
        store.pause(1, 1)
        store.bind(1, 2, ChatBinding(1, 2, 10, 1))
        store.receive(1, 2, Ts3TextEvent(TextTargetMode.PRIVATE, 1, 2, null, "Other occupant", "New"))
        assertNull(registry.consume(token, store.state.value))
    }

    @Test fun clearAndInvalidTargetsCannotProduceDestinations() {
        val store = store()
        val registry = ChatNotificationRegistry({ 0 })
        assertNull(registry.issue(store.state.value, emptySet()))
        assertNull(registry.issue(store.state.value, setOf(0)))
        val token = registry.issue(store)
        registry.clear()
        assertNull(registry.consume(token, store.state.value))
        store.clear()
        assertNull(registry.issue(store.state.value, setOf(1)))
    }

    @Test fun reusedClientIdInSameAttemptStillOpensOnlyTheCapturedHistory() {
        val store = store()
        val registry = ChatNotificationRegistry({ 0 })
        val originalId = store.state.value.conversations.last().id
        val token = registry.issue(store)
        store.bind(
            1,
            1,
            ChatBinding(1, 1, 10, 1),
            participants =
                listOf(
                    Ts3Participant(2, 10, "Different occupant", false, false, false, "different-uid"),
                ),
        )
        store.receive(1, 1, Ts3TextEvent(TextTargetMode.PRIVATE, 1, 2, "different-uid", "Different occupant", "New"))
        val destination = checkNotNull(registry.consume(token, store.state.value))
        assertEquals(setOf(originalId), destination.conversationIds)
        assertNull(store.state.value.conversations.single { it.id == originalId }.target)
        assertNotEquals(originalId, store.state.value.conversations.last().id)
    }
}
