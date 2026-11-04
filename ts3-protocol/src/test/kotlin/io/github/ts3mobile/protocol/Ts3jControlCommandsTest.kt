package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.PacketBody
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class Ts3jControlCommandsTest {
    private val socket =
        CapturingSocket().apply {
            setExceptionHandler { }
            clientId = 7
            setState(ClientConnectionState.CONNECTED)
        }
    private val adapter = LocalTeamspeakClientSocketAdapter(socket)

    @After fun close() {
        socket.closeTransport()
        assertTrue(socket.awaitOperationsStopped(1000))
    }

    @Test fun presenceEncodingEscapesUnicodeAndWhitespaceExactlyOnceAndIncludesAllFlags() {
        socket.onWrite = { reply("error id=0 msg=ok") }
        val presence = OwnPresence(true, "  临时 away | \\ /\n回来  ", true, true)
        assertEquals(ControlStatus.ACCEPTED, adapter.updatePresence(presence, 1000) { true }.status)
        val command = SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.single())
        assertEquals("clientupdate", command.name)
        assertEquals(presence.awayMessage, command.get("client_away_message").value)
        for (flag in listOf("client_away", "client_input_muted", "client_output_muted")) assertEquals("1", command.get(flag).value)
        assertFalse(presence.toString().contains("回来"))
    }

    @Test fun returningFromAwayExplicitlyClearsMessage() {
        socket.onWrite = { reply("error id=0 msg=ok") }
        adapter.updatePresence(OwnPresence(false, "old secret"), 1000) { true }
        val command = SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.single())
        assertEquals("0", command.get("client_away").value)
        assertEquals("", command.get("client_away_message").value)
    }

    @Test fun invalidOrStaleControlNeverEnqueuesAndRejectedStatusIsExplicit() {
        assertEquals(ControlStatus.NOT_SENT, adapter.updatePresence(OwnPresence(awayMessage = "中".repeat(86)), 1000) { true }.status)
        assertEquals(ControlStatus.NOT_SENT, adapter.updatePresence(OwnPresence(), 1000) { false }.status)
        assertEquals(ControlStatus.NOT_SENT, adapter.channelInfo(-1, 1000) { true }.outcome.status)
        assertTrue(socket.writes.isEmpty())
        socket.onWrite = { reply("error id=2568 msg=private-permission-text") }
        val result = adapter.updatePresence(OwnPresence(), 1000) { true }
        assertEquals(ControlStatus.REJECTED, result.status)
        assertEquals(2568, result.errorCode)
        assertFalse(result.toString().contains("private-permission"))
    }

    @Test fun channelDetailsComeFromBoundedReadOnlyResponseAndKeepPlainText() {
        socket.onWrite = {
            reply(
                "channelinfo cid=9 channel_name=Fixture channel_topic=Planning " +
                    "channel_description=[b]plain[/b] channel_maxclients=12 channel_flag_maxclients_unlimited=0",
            )
            reply("error id=0 msg=ok")
        }
        val result = adapter.channelInfo(9, 1000) { true }
        assertEquals(ControlStatus.ACCEPTED, result.outcome.status)
        assertEquals(9, result.details?.id)
        assertEquals("[b]plain[/b]", result.details?.description)
        assertEquals(12, result.details?.maxClients)
        assertEquals(false, result.details?.unlimitedClients)
        assertEquals("channelinfo", SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.single()).name)
    }

    @Test fun mismatchedOrEmptyChannelResponseIsNotSuccessfulInfo() {
        socket.onWrite = {
            reply("channelinfo cid=10 channel_name=wrong")
            reply("error id=0 msg=ok")
        }
        assertNull(adapter.channelInfo(9, 1000) { true }.details)
        socket.onWrite = {
            reply("channelinfo cid=invalid channel_name=wrong")
            reply("error id=0 msg=ok")
        }
        assertNull(adapter.channelInfo(9, 1000) { true }.details)
        socket.onWrite = { reply("error id=0 msg=ok") }
        assertEquals(ControlStatus.UNKNOWN, adapter.channelInfo(9, 1000) { true }.outcome.status)
    }

    @Test fun timedOutPresenceIsUncertainAndIsNotSubmittedTwice() {
        assertEquals(ControlStatus.UNKNOWN, adapter.updatePresence(OwnPresence(), 40) { true }.status)
        assertTrue(socket.awaitOperationsStopped(1000))
        assertEquals(1, socket.writes.size)
        reply("error id=0 msg=ok")
        assertEquals(1, socket.writes.size)
    }

    @Test fun channelAndAwayUpdatesPreserveUnknownOptionalFieldsAndBoundContent() {
        val channel = mapOf("channel_name" to "Fixture", "channel_topic" to "hello").toTs3Channel(1)
        assertEquals("hello", channel.topic)
        assertNull(channel.maxClients)
        val updated = channel.withTeamSpeakUpdates(mapOf("channel_maxclients" to "0", "channel_flag_maxclients_unlimited" to "1"))
        assertEquals(true, updated.unlimitedClients)
        assertEquals("hello", updated.topic)
        val participant =
            Ts3Participant(2, 1, "Peer", false, false, false)
                .withTeamSpeakUpdates(mapOf("client_away" to "1", "client_away_message" to "回来"))
        assertTrue(participant.isAway)
        assertEquals("回来", participant.withTeamSpeakUpdates(mapOf("client_input_muted" to "1")).awayMessage)
        assertFalse(participant.withTeamSpeakUpdates(mapOf("client_away" to "0", "client_away_message" to "")).isAway)
    }

    private fun reply(text: String) {
        socket.process(socket, SingleCommand.parse(ProtocolRole.SERVER, text))
    }

    private class CapturingSocket : StateAwareTeamspeakClientSocket() {
        val writes = CopyOnWriteArrayList<String>()

        @Volatile var onWrite: () -> Unit = {}

        override fun writePacket(body: PacketBody) {
            if (body is PacketBody2Command) {
                writes += body.text
                onWrite()
            }
        }

        override fun close() {
            setState(ClientConnectionState.DISCONNECTED)
        }
    }
}
