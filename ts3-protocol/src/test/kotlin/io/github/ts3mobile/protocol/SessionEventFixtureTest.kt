package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionEventFixtureTest {
    @Test
    fun fixtureReplaysChannelAndParticipantChangesDeterministically() {
        val store = SessionSnapshotStore()
        val snapshots =
            loadFixture().map { fields ->
                applyEvent(store, fields)
                store.snapshot()
            }

        assertEquals(listOf(0), snapshots[0].channels.map(Ts3Channel::clientCount))
        assertEquals(listOf(0, 0), snapshots[1].channels.map(Ts3Channel::clientCount))
        assertEquals(listOf(1, 0), snapshots[2].channels.map(Ts3Channel::clientCount))
        assertEquals(listOf(0, 1), snapshots[3].channels.map(Ts3Channel::clientCount))
        assertEquals("Games Room", snapshots[4].channels.last().name)
        assertEquals(listOf(0, 0), snapshots[5].channels.map(Ts3Channel::clientCount))
        assertEquals(emptyList<Ts3Participant>(), snapshots.last().participants)
    }

    private fun applyEvent(
        store: SessionSnapshotStore,
        fields: List<String>,
    ) {
        when (fields.first()) {
            "CHANNEL" ->
                store.putChannel(
                    Ts3Channel(
                        id = fields[1].toInt(),
                        parentId = fields[2].toInt(),
                        orderAfterId = fields[3].toInt(),
                        name = fields[4],
                        clientCount = 0,
                        hasPassword = fields[5].toBooleanStrict(),
                        isDefault = fields[6].toBooleanStrict(),
                    ),
                )

            "PARTICIPANT" ->
                store.putParticipant(
                    Ts3Participant(
                        id = fields[1].toInt(),
                        channelId = fields[2].toInt(),
                        nickname = fields[3],
                        isTalking = false,
                        isInputMuted = false,
                        isOutputMuted = false,
                        uniqueIdentifier = fields[4],
                    ),
                )

            "MOVE" ->
                store.updateParticipant(fields[1].toInt()) {
                    it.copy(channelId = fields[2].toInt())
                }

            "CHANNEL_NAME" ->
                store.updateChannel(fields[1].toInt()) {
                    it.copy(name = fields[2])
                }

            "REMOVE_PARTICIPANT" -> store.removeParticipant(fields[1].toInt())
            else -> error("Unknown fixture operation: ${fields.first()}")
        }
    }

    private fun loadFixture(): List<List<String>> =
        SessionEventFixtureTest::class.java
            .getResource("/fixtures/session-events.tsv")
            .readText()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .map { it.split("\\t") }
            .toList()
}
