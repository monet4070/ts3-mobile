package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSnapshotStoreTest {
    private val firstChannel = Ts3Channel(1, 0, 0, "Lobby", 0, false, true)
    private val secondChannel = Ts3Channel(2, 0, 1, "Games", 0, false, false)
    private val participant = Ts3Participant(7, 1, "Alice", false, false, false)

    @Test fun attachmentTargetTracksChannelLifetimeRatherThanOwnChannelVisit() {
        val store = SessionSnapshotStore()
        store.putChannel(firstChannel)
        val original = checkNotNull(store.channelLifetime(firstChannel.id))
        store.updateChannel(firstChannel.id) { it.copy(name = "Renamed") }
        store.putParticipant(participant.copy(channelId = secondChannel.id))
        assertEquals(original, store.channelLifetime(firstChannel.id))
        store.removeChannel(firstChannel.id)
        assertNull(store.channelLifetime(firstChannel.id))
        store.putChannel(firstChannel)
        assertNotEquals(original, store.channelLifetime(firstChannel.id))
    }

    @Test
    fun snapshotTracksMovesAndChannelCounts() {
        val store = SessionSnapshotStore()
        store.putChannel(firstChannel)
        store.putChannel(secondChannel)
        store.putParticipant(participant)

        assertEquals(listOf(1, 0), store.snapshot().channels.map { it.clientCount })

        store.updateParticipant(participant.id) { it.copy(channelId = secondChannel.id) }

        assertEquals(listOf(0, 1), store.snapshot().channels.map { it.clientCount })
    }

    @Test
    fun snapshotTracksEditsAndRemovals() {
        val store = SessionSnapshotStore()
        store.putChannel(firstChannel)
        store.putParticipant(participant)

        store.updateChannel(firstChannel.id) { it.copy(name = "Welcome") }
        store.removeParticipant(participant.id)

        val snapshot = store.snapshot()
        assertEquals("Welcome", snapshot.channels.single().name)
        assertEquals(0, snapshot.channels.single().clientCount)
        assertEquals(emptyList<Ts3Participant>(), snapshot.participants)
    }

    @Test
    fun snapshotDerivesOwnCurrentChannel() {
        val snapshot =
            SessionSnapshot(
                channels = listOf(firstChannel, secondChannel),
                participants = listOf(participant),
                ownClientId = participant.id,
            )

        assertEquals(firstChannel.id, snapshot.currentChannelId)
        assertEquals(null, snapshot.copy(ownClientId = 999).currentChannelId)
    }

    @Test
    fun channelFileVisitExpiresOnLeaveAndReturnButNotWhenAnotherParticipantMoves() {
        val store = SessionSnapshotStore()
        store.putParticipant(participant)
        val previous = checkNotNull(store.channelRevision(participant.id, 1))
        store.putParticipant(participant.copy(id = 8, channelId = 2))
        store.updateParticipant(participant.id) { it.copy(nickname = "Renamed") }
        assertEquals(previous, store.channelRevision(participant.id, 1))
        store.updateParticipant(participant.id) { it.copy(channelId = 2) }
        assertEquals(null, store.channelRevision(participant.id, 1))
        store.putParticipant(participant)
        org.junit.Assert.assertNotEquals(previous, store.channelRevision(participant.id, 1))
        store.removeParticipant(participant.id)
        assertEquals(null, store.channelRevision(participant.id, 1))
    }
}
