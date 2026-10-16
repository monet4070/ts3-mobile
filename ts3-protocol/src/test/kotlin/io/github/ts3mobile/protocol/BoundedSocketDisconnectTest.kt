package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter
import com.github.manevolent.ts3j.protocol.NetworkPacket
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState
import com.github.manevolent.ts3j.protocol.packet.PacketBody
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command
import com.github.manevolent.ts3j.protocol.packet.transformation.InitPacketTransformation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the bounded clientdisconnect on the real ts3j socket.
 *
 * The production adapter is driven with sockets that override the library's
 * transport seams, so the command that is written, the confirmation that is
 * accepted and the deadline that ends a blocked write are all exercised without
 * opening a DatagramSocket and without a TeamSpeak server. Event dispatch runs on
 * the calling thread and no library reader or handler thread is started, because
 * the tests move the connection state themselves.
 *
 * No DatagramSocket is ever opened here, so the socket doubles record close()
 * instead of calling it, and the tests that need the real command path inject
 * the library's own handshake transformation.
 */
class BoundedSocketDisconnectTest {
    private val sockets = mutableListOf<StateAwareTeamspeakClientSocket>()

    @After
    fun closeSockets() {
        sockets.forEach { socket ->
            runCatching { socket.setState(ClientConnectionState.DISCONNECTED) }
            runCatching { socket.close() }
        }
        sockets.clear()
        awaitLibraryThreadsStopped()
    }

    @Test
    fun aConnectedSocketSendsOneClientDisconnectCommand() {
        val socket = connectedSocket()
        confirmOwnLeave(socket)

        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)

        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, outcome)
        val command = socket.commands.single()
        assertEquals(ProtocolRole.CLIENT, command.role)
        assertTrue(command.text.startsWith("clientdisconnect"))
        assertTrue(command.text.contains("reasonid=8"))
        assertTrue(command.text.contains("reasonmsg=$REASON"))
        // A confirmed session is released here too, so nothing keeps waiting for
        // acknowledgements the server no longer sends; the caller's own close is
        // idempotent.
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun aLeaveNotificationFromAnotherClientDoesNotConfirmTheLeave() {
        val socket = connectedSocket()
        notifyLeave(socket, clientId = OTHER_CLIENT_ID, delayMillis = CONFIRMATION_DELAY_MILLIS)

        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertEquals(listOf("close", "mark"), socket.releaseOrder)
    }

    @Test
    fun aLocalDisconnectDoesNotConfirmTheLeave() {
        val socket = connectedSocket()
        val watcher =
            Thread {
                Thread.sleep(CONFIRMATION_DELAY_MILLIS)
                runCatching { socket.setState(ClientConnectionState.DISCONNECTED) }
            }
        watcher.isDaemon = true
        watcher.start()

        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertFalse(socket.isDisconnectNotificationPending)
    }

    @Test
    fun aSocketWithoutAnEstablishedSessionReportsNoActiveSessionWithoutWaiting() {
        val fresh = socket(CapturingSocket())
        val handshaking = socket(CapturingSocket())
        handshaking.setState(ClientConnectionState.CONNECTING)

        val startedAt = System.nanoTime()
        val freshOutcome = adapter(fresh).disconnectBounded(LONG_TIMEOUT_MS, REASON)
        val handshakingOutcome = adapter(handshaking).disconnectBounded(LONG_TIMEOUT_MS, REASON)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertEquals(DisconnectOutcome.NO_ACTIVE_SESSION, freshOutcome)
        assertEquals(DisconnectOutcome.NO_ACTIVE_SESSION, handshakingOutcome)
        assertEquals(emptyList<PacketBody2Command>(), fresh.commands)
        assertEquals(emptyList<PacketBody2Command>(), handshaking.commands)
        assertTrue("waited ${elapsedMillis}ms for a session that does not exist", elapsedMillis < LONG_TIMEOUT_MS / 2)
        assertEquals(emptyList<String>(), fresh.releaseOrder)
    }

    @Test
    fun aFailedCommandWriteIsReportedAsFailed() {
        val socket = socket(CapturingSocket(writeFailure = IOException("transport is gone")))
        socket.setState(ClientConnectionState.CONNECTED)

        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)

        assertEquals(DisconnectOutcome.FAILED, outcome)
        assertEquals(listOf("close", "mark"), socket.releaseOrder)
    }

    @Test
    fun anUnacknowledgedCommandIsCutAtTheDeadline() {
        val socket = socket(UnacknowledgedSocket())
        socket.setTransformation(InitPacketTransformation())
        socket.setState(ClientConnectionState.CONNECTED)

        val startedAt = System.nanoTime()
        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        // The library's own retransmission loop waits 1s per attempt for up to 30
        // attempts; the deadline must end it long before that.
        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertTrue("elapsed ${elapsedMillis}ms exceeded the budget", elapsedMillis <= TIMEOUT_MS + RELEASE_SLACK_MS)
        assertEquals(1, socket.attempts.get())
        assertEquals(listOf("close", "mark"), socket.releaseOrder)
    }

    @Test
    fun requestSentIsReportedAfterDatagramWriteEvenWhenAnAckNeverArrives() {
        val socket = socket(UnacknowledgedSocket())
        socket.setTransformation(InitPacketTransformation())
        socket.setState(ClientConnectionState.CONNECTED)
        val sent = AtomicInteger()

        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON) { sent.incrementAndGet() }

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertEquals(1, sent.get())
        assertTrue(socket.awaitOperationsStopped(0))
        assertTrue(libraryThreadNames().none { it.contains("BoundedDisconnect") })
    }

    @Test
    fun aCommandWriteBlockedInsideTheLibraryIsCutAtTheDeadline() {
        val socket = socket(CapturingSocket(blockWrites = true))
        socket.setState(ClientConnectionState.CONNECTED)

        val startedAt = System.nanoTime()
        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertTrue("elapsed ${elapsedMillis}ms exceeded the budget", elapsedMillis <= TIMEOUT_MS + RELEASE_SLACK_MS)
        assertTrue(socket.writeInterrupted)
        assertEquals(listOf("close", "mark"), socket.releaseOrder)
    }

    @Test
    fun aSenderQueuedBehindAnotherCommandIsReleasedWithinOneRetrySlice() {
        val socket = socket(QueuedBehindWriterSocket())
        socket.setTransformation(InitPacketTransformation())
        socket.setState(ClientConnectionState.CONNECTED)
        val holder =
            Thread {
                runCatching { socket.writePacket(disconnectCommand("holder")) }
            }
        holder.isDaemon = true
        holder.start()
        assertTrue(socket.firstWriteEntered.await(1, TimeUnit.SECONDS))

        // The holder owns the library's COMMAND send monitor while it waits for an
        // acknowledgement that never arrives, so the bounded send queues on it. An
        // interrupt cannot break a monitor wait, and the interrupt of the queued
        // sender is delivered before it enters that wait, so the deadline can only
        // return and mark the session disconnected.
        val startedAt = System.nanoTime()
        val outcome = adapter(socket).disconnectBounded(TIMEOUT_MS, REASON)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertTrue("elapsed ${elapsedMillis}ms exceeded the total budget", elapsedMillis <= TIMEOUT_MS + 100L)
        assertTrue("the previous socket operation survived the exit", socket.awaitOperationsStopped(0))
        assertTrue("the exit sender survived the exit", libraryThreadNames().none { it.contains("BoundedDisconnect") })
        assertEquals(ClientConnectionState.DISCONNECTED, socket.state)

        // The release is what lets the queued sender out: the holder leaves its
        // acknowledgement wait at its next retry check, one slice later, instead of
        // exhausting the library's 30 attempt budget.
        val releasedAt = System.nanoTime()
        awaitLibraryThreadsStopped()
        val releaseMillis = (System.nanoTime() - releasedAt) / NANOS_PER_MILLISECOND

        holder.join(THREAD_STOP_TIMEOUT_MILLIS)
        assertFalse(holder.isAlive)
        assertTrue("queued sender took ${releaseMillis}ms to be released", releaseMillis < RETRY_SLICE_BOUND_MILLIS)
    }

    private fun adapter(socket: StateAwareTeamspeakClientSocket): Ts3jClientSocket = LocalTeamspeakClientSocketAdapter(socket)

    private fun connectedSocket(): CapturingSocket {
        val socket = socket(CapturingSocket())
        socket.setState(ClientConnectionState.CONNECTED)
        return socket
    }

    private fun <T : StateAwareTeamspeakClientSocket> socket(value: T): T {
        // Replaces the library's default printing handler so an expected transport
        // failure stays silent, and runs every dispatch on the caller.
        value.setCommandExecutorService(DirectExecutorService())
        value.setExceptionHandler { }
        value.clientId = SELF_CLIENT_ID
        sockets += value
        return value
    }

    private fun confirmOwnLeave(socket: StateAwareTeamspeakClientSocket) {
        notifyLeave(socket, clientId = SELF_CLIENT_ID, delayMillis = CONFIRMATION_DELAY_MILLIS)
    }

    private fun notifyLeave(
        socket: StateAwareTeamspeakClientSocket,
        clientId: Int,
        delayMillis: Long,
    ) {
        val notifier =
            Thread {
                Thread.sleep(delayMillis)
                runCatching { socket.process(socket, leaveNotification(clientId)) }
            }
        notifier.isDaemon = true
        notifier.start()
    }

    private fun leaveNotification(clientId: Int): SingleCommand =
        SingleCommand(
            "notifyclientleftview",
            ProtocolRole.SERVER,
            CommandSingleParameter("clid", clientId.toString()),
            CommandSingleParameter("cfid", "1"),
            CommandSingleParameter("reasonid", "8"),
            CommandSingleParameter("reasonmsg", "left"),
        )

    private fun disconnectCommand(reason: String): PacketBody2Command =
        PacketBody2Command(
            ProtocolRole.CLIENT,
            SingleCommand(
                "clientdisconnect",
                ProtocolRole.CLIENT,
                CommandSingleParameter("reasonid", "8"),
                CommandSingleParameter("reasonmsg", reason),
            ),
        )

    private fun awaitLibraryThreadsStopped() {
        val deadline = System.nanoTime() + THREAD_STOP_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
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

    /**
     * Records the leave command instead of writing it, so the command content and
     * the confirmation path can be asserted without a transport.
     */
    private class CapturingSocket(
        private val writeFailure: IOException? = null,
        private val blockWrites: Boolean = false,
    ) : StateAwareTeamspeakClientSocket() {
        val commands = CopyOnWriteArrayList<PacketBody2Command>()
        val releaseOrder = CopyOnWriteArrayList<String>()
        val closeCalls = AtomicInteger()

        @Volatile
        var writeInterrupted = false

        override fun writePacket(body: PacketBody) {
            writeFailure?.let { throw it }
            if (body is PacketBody2Command) commands += body
            if (!blockWrites) return
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                writeInterrupted = true
                throw InterruptedException("blocked write interrupted")
            }
        }

        override fun close() {
            releaseOrder += "close"
            closeCalls.incrementAndGet()
        }

        override fun markSessionDisconnected() {
            releaseOrder += "mark"
            super.markSessionDisconnected()
        }
    }

    /**
     * Runs the library's real command write and acknowledgement loop while
     * discarding the datagram, so no acknowledgement ever arrives.
     */
    private class UnacknowledgedSocket : StateAwareTeamspeakClientSocket() {
        val attempts = AtomicInteger()
        val releaseOrder = CopyOnWriteArrayList<String>()

        override fun writeDatagram(packet: NetworkPacket) {
            attempts.incrementAndGet()
        }

        override fun close() {
            releaseOrder += "close"
        }

        override fun markSessionDisconnected() {
            releaseOrder += "mark"
            super.markSessionDisconnected()
        }
    }

    /**
     * Runs the library's real command write with a transport that never
     * acknowledges, so the first writer holds the real per-packet-type send
     * monitor while it waits.
     */
    private class QueuedBehindWriterSocket : StateAwareTeamspeakClientSocket() {
        val firstWriteEntered = CountDownLatch(1)
        private val writes = AtomicInteger()

        override fun writeDatagram(packet: NetworkPacket) {
            if (writes.incrementAndGet() == 1) firstWriteEntered.countDown()
        }

        override fun close() = Unit
    }

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
        const val REASON = "task-removed"
        const val TIMEOUT_MS = 250L
        const val LONG_TIMEOUT_MS = 2_000L
        const val RELEASE_SLACK_MS = 400L
        const val CONFIRMATION_DELAY_MILLIS = 40L
        const val RETRY_SLICE_BOUND_MILLIS = 3_000L
        const val THREAD_STOP_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val LIBRARY_THREAD_PREFIX = "TS3J/"
        const val SELF_CLIENT_ID = 7
        const val OTHER_CLIENT_ID = 8
    }
}
