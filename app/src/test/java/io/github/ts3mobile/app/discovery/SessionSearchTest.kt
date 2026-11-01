package io.github.ts3mobile.app.discovery

import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSearchTest {
    private val channel = Ts3Channel(1, 0, 0, "设计频道", 1, false, true, topic = "Planning ROOM")
    private val participant = Ts3Participant(2, 1, "Alice", false, false, false, "fixture-uid")
    private val snapshot = SessionSnapshot(listOf(channel), listOf(participant))

    @Test fun matchesNamesTopicsAndNicknamesWithWhitespaceAndCaseInsensitiveQuery() {
        assertEquals(listOf(channel), searchSession(snapshot, "  room ").channels)
        assertEquals(listOf(channel), searchSession(snapshot, "设计").channels)
        assertEquals(listOf(participant), searchSession(snapshot, "ALICE").participants)
        assertEquals(0, searchSession(snapshot, "missing").channels.size)
    }

    @Test fun resultsAreBoundedAndInputCannotGrowUnbounded() {
        val large = SessionSnapshot((1..1000).map { channel.copy(id = it) }, (1..1000).map { participant.copy(id = it) })
        val results = searchSession(large, "")
        assertEquals(50, results.channels.size)
        assertEquals(50, results.participants.size)
    }

    @Test fun userJoinResolvesLatestChannelAndRejectsReusedClientIdOrMissingIdentity() {
        assertEquals(1, resolveParticipantChannel(snapshot, 2, "fixture-uid"))
        assertNull(resolveParticipantChannel(snapshot, 2, "different"))
        assertNull(resolveParticipantChannel(snapshot, 2, ""))
        assertNull(resolveParticipantChannel(snapshot.copy(channels = emptyList()), 2, "fixture-uid"))
        val moved = snapshot.copy(channels = listOf(channel.copy(id = 9)), participants = listOf(participant.copy(channelId = 9)))
        assertEquals(9, resolveParticipantChannel(moved, 2, "fixture-uid"))
    }
}
