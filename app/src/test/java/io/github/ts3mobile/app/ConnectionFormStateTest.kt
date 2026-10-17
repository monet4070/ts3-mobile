package io.github.ts3mobile.app

import io.github.ts3mobile.app.history.ServerHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionFormStateTest {
    @Test
    fun selectingADifferentEndpointClearsThePassword() {
        val form = ConnectionFormState(host = "old.example.com", port = "9987", password = "secret")

        val updated = form.withSelectedHistoryEntry(ServerHistoryEntry("new.example.com", 10011))

        assertEquals("new.example.com", updated.host)
        assertEquals("10011", updated.port)
        assertEquals("", updated.password)
        assertFalse(updated.submitted)
    }

    @Test
    fun selectingTheSameEndpointKeepsThePassword() {
        val form = ConnectionFormState(host = "voice.example.com", port = "9987", password = "secret")

        val updated = form.withSelectedHistoryEntry(ServerHistoryEntry("voice.example.com", 9987))

        assertEquals("secret", updated.password)
    }

    @Test
    fun selectingTheSameHostOnADifferentPortClearsThePassword() {
        val form = ConnectionFormState(host = "voice.example.com", port = "9987", password = "secret")

        val updated = form.withSelectedHistoryEntry(ServerHistoryEntry("voice.example.com", 10011))

        assertEquals("", updated.password)
    }
}
