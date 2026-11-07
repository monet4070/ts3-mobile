package io.github.ts3mobile.app.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatNotificationEntryTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"

    private fun destination(token: String) = ChatNotificationDestination(token, "session", 0, setOf(4))

    @Test fun coldRequestWaitsForBindingAndResolvesOnlyOnce() {
        val entry = ChatNotificationEntry()
        entry.accept(first)
        assertNull(entry.destination.value)
        var calls = 0
        repeat(2) {
            entry.resolve {
                calls++
                destination(it)
            }
        }
        assertEquals(1, calls)
        assertEquals(first, entry.destination.value?.requestId)
        entry.consumed(first)
        assertNull(entry.destination.value)
    }

    @Test fun newerIntentSupersedesPendingOrResolvedNavigation() {
        val entry = ChatNotificationEntry()
        entry.accept(first)
        entry.accept(second)
        entry.resolve(::destination)
        assertEquals(second, entry.destination.value?.requestId)
        entry.consumed(first)
        assertEquals(second, entry.destination.value?.requestId)
        entry.accept(first)
        assertNull(entry.destination.value)
        entry.resolve(::destination)
        assertEquals(first, entry.destination.value?.requestId)
    }

    @Test fun userNavigationCancelsPendingRequestAndInvalidTokenOnlyOpensApp() {
        val entry = ChatNotificationEntry()
        entry.accept(first)
        entry.cancel()
        entry.resolve { error("Cancelled request must not resolve") }
        entry.accept("untrusted")
        entry.resolve { error("Malformed token must not resolve") }
        entry.accept(first)
        entry.resolve { null }
        assertNull(entry.destination.value)
    }
}
