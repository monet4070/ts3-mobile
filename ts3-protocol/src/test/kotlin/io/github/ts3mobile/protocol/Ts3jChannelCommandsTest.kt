package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.Command
import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.response.CommandResponse
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.util.Ts3Crypt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class Ts3jChannelCommandsTest {
    private val socket =
        FixtureSocket().apply {
            clientId = 7
            setState(ClientConnectionState.CONNECTED)
        }
    private val adapter = LocalTeamspeakClientSocketAdapter(socket, channelCommandTimeoutMs = 500)

    @After fun close() {
        socket.closeTransport()
        assertTrue(socket.awaitOperationsStopped(1_000))
    }

    @Test fun commandsKeepTheirWireParametersAndUseOnlyTimedResponseWaits() {
        adapter.subscribeAll()
        adapter.joinChannel(17, "fixture password")

        assertEquals(listOf("channelsubscribeall", "clientmove"), socket.submitted.map { it.name })
        val move = socket.submitted.last() as SingleCommand
        assertEquals("17", move.get("cid").value)
        assertEquals("7", move.get("clid").value)
        assertEquals(Ts3Crypt.hashPassword("fixture password"), move.get("cpw").value)
        assertEquals(2, socket.budgets.size)
        assertTrue(socket.budgets.all { it in 1..500 })
    }

    @Test fun readinessWithoutConfirmationDoesNotReturnSuccess() {
        socket.reply = { throw TimeoutException("fixture deadline") }

        assertThrows(SocketTimeoutException::class.java) { adapter.subscribeAll() }
        assertEquals(listOf("channelsubscribeall"), socket.submitted.map { it.name })
    }

    @Test fun moveDistinguishesUnconfirmedNotSubmittedAndRejected() {
        socket.reply = { throw TimeoutException("fixture deadline") }
        assertThrows(ChannelMoveUnconfirmedException::class.java) { adapter.joinChannel(17, "") }
        assertThrows(ChannelMoveNotSubmittedException::class.java) { adapter.joinChannel(18, "") { false } }

        socket.reply = { throw CommandException("synthetic refusal", 2568) }
        val rejected = assertThrows(CommandException::class.java) { adapter.joinChannel(19, "") }
        assertEquals(2568, rejected.errorId)
        assertEquals(listOf("17", "19"), socket.submitted.map { (it as SingleCommand).get("cid").value })
    }

    @Test fun cancelingTheCallerStopsItsResponseWaitWithoutASecondSubmission() {
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        socket.reply = {
            waiting.countDown()
            release.await()
            emptyList()
        }
        val caller = Thread { runCatching { adapter.joinChannel(17, "") }.onFailure(outcome::set) }
        caller.start()
        try {
            assertTrue(waiting.await(1, SECONDS))
            caller.interrupt()
            caller.join(1_000)
            assertFalse(caller.isAlive)
            assertTrue(outcome.get() is InterruptedException)
            assertTrue(socket.awaitOperationsStopped(1_000))
            assertEquals(listOf("clientmove"), socket.submitted.map { it.name })
        } finally {
            release.countDown()
            caller.interrupt()
            caller.join(1_000)
        }
    }

    private class FixtureSocket : StateAwareTeamspeakClientSocket() {
        val submitted = CopyOnWriteArrayList<Command>()
        val budgets = CopyOnWriteArrayList<Long>()

        @Volatile var reply: () -> Iterable<SingleCommand> = { emptyList() }

        override fun close() {
            setState(ClientConnectionState.DISCONNECTED)
        }

        override fun executeCommand(command: Command): CommandResponse<Iterable<SingleCommand>> {
            submitted += command
            return object : CommandResponse<Iterable<SingleCommand>> {
                override fun getCommand() = command

                override fun get(timeout: Long): Iterable<SingleCommand> {
                    budgets += timeout
                    return reply()
                }

                override fun get(): Iterable<SingleCommand> = error("Unbounded read is not allowed")
            }
        }
    }
}
