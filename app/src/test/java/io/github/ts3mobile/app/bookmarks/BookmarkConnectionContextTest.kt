package io.github.ts3mobile.app.bookmarks

import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkConnectionContextTest {
    private val connection = BookmarkConnection("session-a", "Live.Example.com", 9999, "Active nickname")
    private val channel = Ts3Channel(2, 1, 0, "Room", 0, true, false)
    private val context = BookmarkConnectionContext(connection, listOf(channel), 2)
    private val entry = ServerBookmark("fixture", name = "Fixture", host = " live.example.com ", port = 9999, defaultChannelId = 2)
    private val selection = BookmarkChannelSelection("session-a", 2)

    @Test fun endpointMatchingUsesTrimCaseAndPortButDoesNotGuessAliases() {
        assertTrue(context.accepts(entry, selection))
        assertFalse(context.accepts(entry.copy(port = 9987), selection))
        assertFalse(context.accepts(entry.copy(host = "alias.example.com"), selection))
        assertFalse(connection.toString().contains(connection.host))
        assertFalse(connection.toString().contains(connection.nickname))
    }

    @Test fun sessionReplacementDeletionAndAmbiguousChannelIdsInvalidateSelection() {
        assertFalse(context.copy(connection = connection.copy(token = "session-b")).accepts(entry, selection))
        assertFalse(context.copy(channels = emptyList()).accepts(entry, selection))
        assertFalse(context.copy(channels = listOf(channel, channel)).accepts(entry, selection))
        assertFalse(context.accepts(entry.copy(defaultChannelId = 3), selection))
    }

    @Test fun noChannelSelectionStillBindsTheCapturedCurrentConnection() {
        val noChannel = selection.copy(channelId = null)
        assertTrue(context.accepts(entry.copy(defaultChannelId = null), noChannel))
        assertFalse(context.copy(connection = connection.copy(token = "other")).accepts(entry.copy(defaultChannelId = null), noChannel))
    }

    @Test fun onlyAnEstablishedConnectedStateExposesTheLiveContext() {
        val state = TeamSpeakServiceState(bookmarkConnection = connection, snapshot = SessionSnapshot(channels = listOf(channel)))
        ConnectionPhase.entries.filter { it != ConnectionPhase.CONNECTED }.forEach { phase ->
            assertNull(state.copy(status = ConnectionStatus(phase)).bookmarkContext())
        }
        assertEquals(connection, state.copy(status = ConnectionStatus(ConnectionPhase.CONNECTED)).bookmarkContext()?.connection)
    }

    @Test fun duplicateNamesAreDistinguishedByParentPathAndId() {
        val channels =
            listOf(
                channel.copy(id = 1, parentId = 0, name = "North"),
                channel,
                channel.copy(id = 3, parentId = 0, name = "South"),
                channel.copy(id = 4, parentId = 3),
            )
        assertEquals(listOf("North / Room (#2)", "South / Room (#4)"), bookmarkChannelChoices(channels, "room").map { it.path })
        assertEquals(listOf(4), bookmarkChannelChoices(channels, "south / room").map { it.id })
    }

    @Test fun cyclesOrphansAndVeryDeepPathsAreBoundedAndRemainSelectable() {
        val channels = (1..100).map { channel.copy(id = it, parentId = it - 1) } + channel.copy(id = 200, parentId = 201)
        val deep = bookmarkChannelChoices(channels, "(#100)").single()
        assertTrue(deep.path.startsWith("… / "))
        assertEquals("Room (#200)", bookmarkChannelChoices(channels, "(#200)").single().path)
        val cyclic = listOf(channel.copy(id = 1, parentId = 2), channel.copy(id = 2, parentId = 1))
        assertEquals(2, bookmarkChannelChoices(cyclic).size)
    }

    @Test fun duplicateIdsAreExcludedAndResultLimitCanBeRefined() {
        val many = (1..150).map { channel.copy(id = it, parentId = 0) }
        assertEquals(100, bookmarkChannelChoices(many).size)
        assertEquals(listOf(150), bookmarkChannelChoices(many, "(#150)").map { it.id })
        assertFalse(bookmarkChannelChoices(listOf(channel, channel)).any { it.id == 2 })
    }
}
