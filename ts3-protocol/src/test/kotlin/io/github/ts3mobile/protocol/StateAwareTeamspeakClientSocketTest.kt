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
 * socket is opened: the tests move the connection state themselves instead of
 * depending on when the library notices a missing transport. Event dispatch runs on
 * the calling thread and the library's default printing exception handler is
 * replaced, so the expected handshake write failure stays silent.
 *
 * A CONNECTING transition still makes the library start a daemon reader thread and
 * a daemon handler thread. Both are interrupted by the DISCONNECTED transition the
 * test drives, and every socket is closed in @After, which also waits until no
 * library thread is left running.
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

        assertTrue(socket.transportConnected)
    }

    @Test
    fun aDisconnectAfterConnectingLatchesTheTransportClosed() {
        val socket = newSocket()

        socket.startAttemptAndDisconnect()

        assertFalse(socket.transportConnected)
    }

    @Test
    fun theClosedLatchIsNotClearedByALaterAttempt() {
        val socket = newSocket()

        socket.startAttemptAndDisconnect()
        runCatching { socket.setState(ClientConnectionState.CONNECTING) }
        socket.setState(ClientConnectionState.CONNECTED)

        // The latch is one-way: a later attempt or a later connected state must not
        // re-open it. A new attempt always uses a new socket instance, so this can
        // never hide a healthy session.
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

    /**
     * Drives one attempt and the disconnect that follows it.
     *
     * The library's CONNECTING handler writes the INIT1 handshake packet, and this
     * test never opens a socket, so that write fails and the call throws. The
     * connection state and the latch input are applied before the write, so the test
     * moves the state to DISCONNECTED itself rather than waiting for the library's
     * reader thread.
     */
    private fun StateAwareTeamspeakClientSocket.startAttemptAndDisconnect() {
        runCatching { setState(ClientConnectionState.CONNECTING) }
        setState(ClientConnectionState.DISCONNECTED)
    }

    /**
     * The library starts daemon threads named TS3J/NetworkReader and
     * TS3J/NetworkHandler for a CONNECTING transition. Both must be gone before the
     * test ends, otherwise an abandoned socket would keep reading in the background.
     */
    private fun awaitLibraryThreadsStopped() {
        val deadline = System.nanoTime() + THREAD_STOP_TIMEOUT_NANOS
        while (true) {
            val running = libraryThreadNames()
            if (running.isEmpty()) return
            if (System.nanoTime() > deadline) {
                fail("library threads still running: $running")
            }
            Thread.sleep(POLL_MILLIS)
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
        const val THREAD_STOP_TIMEOUT_NANOS = 5_000_000_000L
        const val POLL_MILLIS = 5L
        const val LIBRARY_THREAD_PREFIX = "TS3J/"
    }
}
