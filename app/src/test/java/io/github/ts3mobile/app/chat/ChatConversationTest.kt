package io.github.ts3mobile.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationTest {
    @Test fun existingAndFullConversationsNeverAllocateAnotherId() {
        var calls = 0
        val state = ChatState()
        val existing =
            state.ensureConversation(ChatConversationKey.Channel, null, null, {
                calls++
                1
            }, 2)
        assertEquals(state, existing)
        val added =
            state.ensureConversation(ChatConversationKey.User("fixture"), "Fixture", null, {
                calls++
                1
            }, 2)
        val full =
            added.ensureConversation(ChatConversationKey.User("other"), "Other", null, {
                calls++
                2
            }, 2)
        assertEquals(1, calls)
        assertEquals(2, full.conversations.size)
        assertTrue(full.conversationLimitReached)
    }

    @Test fun attemptInvalidationRemovesOnlyEphemeralConversationsAndSelection() {
        val temporary = ChatConversation(1, ChatConversationKey.AttemptUser(1, 8, 1), draft = "temporary")
        val stable = ChatConversation(2, ChatConversationKey.User("fixture"), draft = "stable")
        val state = ChatState(conversations = listOf(ChatConversation(), temporary, stable), selected = temporary.key)
        val updated = state.withoutAttemptTargets()
        assertEquals(ChatConversationKey.Channel, updated.selected)
        assertFalse(updated.conversations.any { it.key == temporary.key })
        assertEquals("stable", updated.conversations.last().draft)
    }
}
