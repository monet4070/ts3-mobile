package io.github.ts3mobile.app.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerHistoryPrefillTest {
    private val entries =
        listOf(
            ServerHistoryEntry("recent.example.com", 9987),
            ServerHistoryEntry("older.example.com", 10011),
        )

    @Test
    fun prefillUsesTheMostRecentSuccessWhenTheFormIsUntouched() {
        assertEquals(
            "recent.example.com" to "9987",
            ServerHistoryPrefill.hostAndPort(entries, connectionFieldsEdited = false),
        )
    }

    @Test
    fun aLateLoadDoesNotOverwriteAnEditedAddressOrPort() {
        assertNull(ServerHistoryPrefill.hostAndPort(entries, connectionFieldsEdited = true))
    }

    @Test
    fun anEmptyHistoryHasNothingToPrefill() {
        assertNull(ServerHistoryPrefill.hostAndPort(emptyList(), connectionFieldsEdited = false))
    }
}
