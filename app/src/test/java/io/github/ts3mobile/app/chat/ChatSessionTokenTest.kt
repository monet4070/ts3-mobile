package io.github.ts3mobile.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSessionTokenTest {
    @Test fun reconnectPreservesTokenAndClearRemovesIt() {
        val store = ChatStore()
        store.begin(1)
        val token = store.state.value.sessionToken
        assertNotNull(token)
        store.bind(1, 1, ChatBinding(1, 1, 1, 1))
        store.pause(1, 1)
        store.bind(1, 2, ChatBinding(1, 2, 1, 1))
        assertEquals(token, store.state.value.sessionToken)
        store.clear()
        assertNull(store.state.value.sessionToken)
    }

    @Test fun sameNumericScopeCannotReuseOldSessionOrServiceToken() {
        val first = ChatStore()
        first.begin(1)
        val old = first.state.value.sessionToken
        first.begin(1)
        assertNotEquals(old, first.state.value.sessionToken)
        val replacement = ChatStore()
        replacement.begin(1)
        assertNotEquals(first.state.value.sessionToken, replacement.state.value.sessionToken)
    }
}
