package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the ts3j delegate that mirrors the socket's own connection
 * state behind a one-way latch and flags a server-issued disconnect before the
 * listener notification is dispatched.
 *
 * The socket is driven directly through the library's public state machine and no
 * socket is opened. Event dispatch runs on the calling thread and the library's
 * default printing exception handler is replaced, so the expected transport failure
 * stays silent.
 *
 * A connection attempt cannot complete without a real socket: the library's
 * CONNECTING handler writes the INIT1 handshake packet, that write fails, and the
 * library's own reader thread tears the connection back down to DISCONNECTED. The
 * library starts a daemon reader thread and a daemon handler thread for that
 * transition, so every test waits for both threads to stop and closes its sockets in
 * @After. The attempt input and the latch are applied before the failing write,
 * which is why the teardown is the disconnect the latch tests observe.
 */
class StateAwareTeamspeakClientSocketTest {
    private val sockets = mutableListOf<StateAwareTeamspeakClientSocket>()

    @After
    fun closeSockets() {
        sockets.forEach { socket ->
            // A DISCONNECTED transition interrupts the library's reader and handler
            // threads. close() is best effort here: no DatagramSocket was ever
            // opened, so the library's own close fails before it reaches its
            // executor.
            runCatching { socket.setState(ClientConnectionState.DISCONNECTED) }
            runCatching { socket.close() }
        }
        sockets.clear()
        awaitLibraryThreadsStopped()
    }

    @Test
    fun aFreshSocketReportsNoTransportStateAndNoPendingDisconnect() {
        val socket = newSocket()

        assertEquals(ClientConnectionState.DISCONNECTED, socket.state)
        assertFalse(socket.isConnected)
        assertFalse(socket.transportConnected)
        assertFalse(socket.isDisconnectNotificationPending)
    }

    @Test
    fun theLibraryConstructorDisconnectDoesNotLatchTheTransportClosed() {
        val socket = newSocket()

        socket.setState(ClientConnectionState.DISCONNECTED)
        socket.setState(ClientConnectionState.CONNECTED)

        assertTrue(socket.isConnected)
        assertTrue(socket.transportConnected)
    }

    @Test
    fun aDisconnectAfterConnectingLatchesTheTransportClosed() {
        val socket = newSocket()

        socket.enterConnectingAndSettle()
        socket.setState(ClientConnectionState.CONNECTED)

        // The library state reports a live session again while the latch keeps the
        // confirmed loss visible.
        assertTrue(socket.isConnected)
        assertFalse(socket.transportConnected)
    }

    @Test
    fun theClosedLatchSurvivesAnotherConnectionAttempt() {
        val socket = newSocket()

        socket.enterConnectingAndSettle()
        socket.enterConnectingAndSettle()
        socket.setState(ClientConnectionState.CONNECTED)

        // The latch is one-way: neither a later attempt nor a later connected state
        // may clear it. A new attempt always uses a new socket instance, so this can
        // never hide a healthy session.
        assertTrue(socket.isConnected)
        assertFalse(socket.transportConnected)
    }

    @Test
    fun aSelfLeaveNotificationMarksTheDisconnectPendingBeforeDispatch() {
        val socket = newSocket()
        socket.clientId = SELF_CLIENT_ID

        socket.process(socket, leaveNotification(SELF_CLIENT_ID))

        assertTrue(socket.isDisconnectNotificationPending)
    }

    @Test
    fun anotherClientsLeaveNotificationDoesNotMarkTheDisconnectPending() {
        val socket = newSocket()
        socket.clientId = SELF_CLIENT_ID

        socket.process(socket, leaveNotification(OTHER_CLIENT_ID))

        assertFalse(socket.isDisconnectNotificationPending)
    }

    private fun newSocket(): StateAwareTeamspeakClientSocket {
        val socket = StateAwareTeamspeakClientSocket()
        // Replaces the library's default printing handler so the expected
        // transport failure stays silent, and runs every dispatch on the caller.
        socket.setCommandExecutorService(DirectExecutorService())
        socket.setExceptionHandler { }
        sockets += socket
        return socket
    }

    private fun StateAwareTeamspeakClientSocket.enterConnectingAndSettle() {
        runCatching { setState(ClientConnectionState.CONNECTING) }
        awaitDisconnected()
        awaitLibraryThreadsStopped()
    }

    private fun StateAwareTeamspeakClientSocket.awaitDisconnected() {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        while (state != ClientConnectionState.DISCONNECTED) {
            if (System.nanoTime() > deadline) {
                fail("socket stayed in $state instead of ${ClientConnectionState.DISCONNECTED}")
            }
            Thread.sleep(SETTLE_POLL_MS)
        }
    }

    /**
     * The library starts daemon threads named TS3J/NetworkReader and
     * TS3J/NetworkHandler for a CONNECTING transition. Both must be gone before the
     * test ends, otherwise an abandoned socket would keep reading in the background.
     */
    private fun awaitLibraryThreadsStopped() {
        val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
        while (true) {
            val running = libraryThreadNames()
            if (running.isEmpty()) return
            if (System.nanoTime() > deadline) {
                fail("library threads still running: $running")
            }
            Thread.sleep(SETTLE_POLL_MS)
        }
    }

    private fun libraryThreadNames(): List<String> =
        Thread.getAllStackTraces().keys
            .map { it.name }
            .filter { it.startsWith(LIBRARY_THREAD_PREFIX) }

    private fun leaveNotification(clientId: Int): SingleCommand =
        SingleCommand(
            "notifyclientleftview",
            ProtocolRole.SERVER,
            CommandSingleParameter("clid", clientId.toString()),
            CommandSingleParameter("cfid", "1"),
            CommandSingleParameter("reasonid", "8"),
            CommandSingleParameter("reasonmsg", "left"),
        )

    private class DirectExecutorService : AbstractExecutorService() {
        override fun shutdown() = Unit

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown(): Boolean = false

        override fun isTerminated(): Boolean = false

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = true

        override fun execute(command: Runnable) = command.run()
    }

    private companion object {
        const val SELF_CLIENT_ID = 7
        const val OTHER_CLIENT_ID = 8
        const val SETTLE_TIMEOUT_NANOS = 5_000_000_000L
        const val SETTLE_POLL_MS = 5L
        const val LIBRARY_THREAD_PREFIX = "TS3J/"
    }
}
