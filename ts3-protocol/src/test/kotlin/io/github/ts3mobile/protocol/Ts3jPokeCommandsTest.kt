package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.PacketBody
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference

class Ts3jPokeCommandsTest {
    private val socket =
        CapturingSocket().apply {
            setExceptionHandler { }
            clientId = 7
            setState(ClientConnectionState.CONNECTED)
        }
    private val adapter = LocalTeamspeakClientSocketAdapter(socket)
    private val target = TextTarget.Private(8, "fixture-uid")

    @After fun close() {
        socket.closeTransport()
        assertTrue(socket.awaitOperationsStopped(1_000))
    }

    @Test fun actualClientPokeEncodingPreservesReasonAndAllowsEmptyReason() {
        socket.onWrite = { reply(0) }
        val reason = " 中文😀 | \\ \n "
        assertEquals(TextSendOutcome.Accepted, adapter.pokeClient(target, reason, 1_000) { true })
        val parsed = SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.single())
        assertEquals("clientpoke", parsed.name)
        assertEquals("8", parsed.get("clid").value)
        assertEquals(reason, parsed.get("msg").value)
        assertEquals(TextSendOutcome.Accepted, adapter.pokeClient(target, "", 1_000) { true })
        assertEquals("", SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.last()).get("msg").value)
    }

    @Test fun byteLimitAndChangedContextDoNotEnqueueAndPermissionErrorsAreCategorized() {
        assertEquals(
            TextSendOutcome.NotSent(TextNotSentReason.INVALID_INPUT),
            adapter.pokeClient(target, "😀".repeat(26), 1_000) { true },
        )
        assertEquals(
            TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED),
            adapter.pokeClient(target, "", 1_000) { false },
        )
        assertTrue(socket.writes.isEmpty())
        socket.onWrite = { reply(2568) }
        val result = adapter.pokeClient(target, "", 1_000) { true }
        assertEquals(TextSendOutcome.Rejected(TextRejection.PERMISSION, 2568), result)
        assertFalse(result.toString().contains("private-error"))
    }

    @Test fun queuedPokeMayRunAfterTimeoutOnceAndIsNeverRetried() {
        socket.executeCommand(SingleCommand("clientinfo", ProtocolRole.CLIENT))
        assertEquals(TextSendOutcome.Unknown, adapter.pokeClient(target, "once", 30) { true })
        assertTrue(socket.awaitOperationsStopped(1_000))
        assertEquals(1, socket.writes.size)
        reply(0)
        assertEquals(2, socket.writes.size)
        assertTrue(socket.writes.last().startsWith("clientpoke"))
        reply(0)
        assertEquals(2, socket.writes.size)
    }

    @Test fun textAndPokeShareTheSamePendingWorker() {
        val entered = CountDownLatch(1)
        socket.onWrite = { entered.countDown() }
        val outcome = AtomicReference<TextSendOutcome>()
        val worker = Thread { outcome.set(adapter.pokeClient(target, "one", 2_000) { true }) }
        worker.start()
        assertTrue(entered.await(1, SECONDS))
        assertEquals(
            TextSendOutcome.NotSent(TextNotSentReason.BUSY),
            adapter.sendTextMessage(TextTarget.Channel(1), "second", 1_000) { true },
        )
        reply(0)
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertEquals(TextSendOutcome.Accepted, outcome.get())
        assertEquals(1, socket.writes.size)
    }

    private fun reply(code: Int) {
        socket.process(socket, SingleCommand.parse(ProtocolRole.SERVER, "error id=$code msg=private-error"))
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
