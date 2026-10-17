package io.github.ts3mobile.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerHistoryPolicyTest {
    @Test
    fun rememberPlacesTheNewestEntryFirst() {
        val existing = listOf(ServerHistoryEntry("a.example.com", 9987))
        val updated = ServerHistoryPolicy.remember(existing, ServerHistoryEntry("b.example.com", 9987))

        assertEquals(
            listOf(ServerHistoryEntry("b.example.com", 9987), ServerHistoryEntry("a.example.com", 9987)),
            updated,
        )
    }

    @Test
    fun rememberMovesAnExistingEndpointToTheFrontWithoutDuplicating() {
        val existing =
            listOf(
                ServerHistoryEntry("a.example.com", 9987),
                ServerHistoryEntry("b.example.com", 9987),
            )

        val updated = ServerHistoryPolicy.remember(existing, ServerHistoryEntry("b.example.com", 9987))

        assertEquals(
            listOf(ServerHistoryEntry("b.example.com", 9987), ServerHistoryEntry("a.example.com", 9987)),
            updated,
        )
    }

    @Test
    fun rememberDeduplicatesHostsCaseInsensitively() {
        val existing = listOf(ServerHistoryEntry("Voice.Example.com", 9987))

        val updated = ServerHistoryPolicy.remember(existing, ServerHistoryEntry("voice.example.com", 9987))

        assertEquals(listOf(ServerHistoryEntry("voice.example.com", 9987)), updated)
    }

    @Test
    fun rememberKeepsTheSameHostOnDifferentPortsApart() {
        val existing = listOf(ServerHistoryEntry("voice.example.com", 9987))

        val updated = ServerHistoryPolicy.remember(existing, ServerHistoryEntry("voice.example.com", 10011))

        assertEquals(2, updated.size)
    }

    @Test
    fun rememberCapsTheListAtTheMaximum() {
        var entries = emptyList<ServerHistoryEntry>()
        repeat(ServerHistoryPolicy.MAX_ENTRIES + 5) { index ->
            entries = ServerHistoryPolicy.remember(entries, ServerHistoryEntry("host$index.example.com", 9987))
        }

        assertEquals(ServerHistoryPolicy.MAX_ENTRIES, entries.size)
        assertEquals(ServerHistoryEntry("host14.example.com", 9987), entries.first())
    }

    @Test
    fun rememberIgnoresInvalidEndpoints() {
        val existing = listOf(ServerHistoryEntry("voice.example.com", 9987))

        assertEquals(existing, ServerHistoryPolicy.remember(existing, ServerHistoryEntry("", 9987)))
        assertEquals(existing, ServerHistoryPolicy.remember(existing, ServerHistoryEntry("voice.example.com", 0)))
        assertEquals(existing, ServerHistoryPolicy.remember(existing, ServerHistoryEntry("voice.example.com", 70000)))
        assertEquals(existing, ServerHistoryPolicy.remember(existing, ServerHistoryEntry("bad host", 9987)))
    }

    @Test
    fun removeDropsTheMatchingEndpoint() {
        val existing =
            listOf(
                ServerHistoryEntry("a.example.com", 9987),
                ServerHistoryEntry("b.example.com", 9987),
            )

        assertEquals(
            listOf(ServerHistoryEntry("a.example.com", 9987)),
            ServerHistoryPolicy.remove(existing, ServerHistoryEntry("B.example.com", 9987)),
        )
    }

    @Test
    fun mostRecentReturnsTheFirstEntryOrNull() {
        assertNull(ServerHistoryPolicy.mostRecent(emptyList()))
        assertEquals(
            ServerHistoryEntry("a.example.com", 9987),
            ServerHistoryPolicy.mostRecent(listOf(ServerHistoryEntry("a.example.com", 9987))),
        )
    }
}
