package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.Command
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.command.response.CommandResponse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Ts3jTextCommandsTest {
    private val socket =
        CapturingSocket().apply {
            setExceptionHandler { }
            clientId = 7
            setState(ClientConnectionState.CONNECTED)
        }
    private val adapter = LocalTeamspeakClientSocketAdapter(socket)

    @After fun close() {
        socket.closeTransport()
        assertTrue(socket.awaitOperationsStopped(1_000))
    }

    @Test fun pinnedEncoderPreservesUnicodeAndWhitespaceExactlyOnce() {
        val body = "  中文😀 space\nline | \\ /\t  "
        socket.onWrite = { reply(0) }
        assertEquals(TextSendOutcome.Accepted, adapter.sendTextMessage(TextTarget.Channel(19), body, 1_000) { true })
        val parsed = SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.single())
        assertEquals("sendtextmessage", parsed.name)
        assertEquals("2", parsed.get("targetmode").value)
        assertEquals("19", parsed.get("target").value)
        assertEquals(body, parsed.get("msg").value)
    }

    @Test fun explicitServerErrorIsCategorizedWithoutRawMessage() {
        socket.onWrite = { reply(2568) }
        val result = adapter.sendTextMessage(TextTarget.Channel(1), "body", 1_000) { true }
        assertEquals(TextSendOutcome.Rejected(TextRejection.PERMISSION, 2568), result)
        assertFalse(result.toString().contains("private-error"))
    }

    @Test fun invalidatedContextAndInvalidBodyNeverEnqueue() {
        assertEquals(
            TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED),
            adapter.sendTextMessage(TextTarget.Channel(1), "hello", 1_000) { false },
        )
        assertEquals(
            TextSendOutcome.NotSent(TextNotSentReason.INVALID_INPUT),
            adapter.sendTextMessage(TextTarget.Channel(1), "中".repeat(342), 1_000) { true },
        )
        assertTrue(socket.writes.isEmpty())
    }

    @Test fun timeoutEndsWaitButPinnedQueueCanStillSendLaterOnlyOnce() {
        // A previous real command is awaiting its response. Text is enqueued
        // behind it, using the pinned FIFO implementation, without any network.
        socket.executeCommand(SingleCommand("clientinfo", ProtocolRole.CLIENT))
        val outcome = adapter.sendTextMessage(TextTarget.Channel(3), "later", 40) { true }
        assertEquals(TextSendOutcome.Unknown, outcome)
        assertTrue(socket.awaitOperationsStopped(1_000))
        assertEquals(1, socket.writes.size)
        reply(0) // Finishing clientinfo releases the timed-out queued text.
        assertEquals(2, socket.writes.size)
        assertTrue(socket.writes.last().startsWith("sendtextmessage"))
        reply(0)
        assertEquals(2, socket.writes.size)
        assertEquals(TextSendOutcome.Unknown, outcome)
    }

    @Test fun channelMoveEnqueuesAfterSubmittedTextAndUsesOriginalTarget() {
        val entered = CountDownLatch(1)
        socket.onWrite = { entered.countDown() }
        val textResult = AtomicReference<TextSendOutcome>()
        val sender = Thread { textResult.set(adapter.sendTextMessage(TextTarget.Channel(3), "original", 1_000) { true }) }
        sender.start()
        assertTrue(entered.await(1, SECONDS))
        val joining = Thread { adapter.joinChannel(5, "") }
        joining.start()
        assertTrue(socket.moveEnqueued.await(1, SECONDS))
        reply(0)
        reply(0)
        sender.join(1_000)
        joining.join(1_000)
        assertFalse(sender.isAlive)
        assertFalse(joining.isAlive)
        assertEquals(
            listOf("sendtextmessage", "clientmove"),
            socket.writes.map {
                SingleCommand.parse(ProtocolRole.CLIENT, it).name
            },
        )
        assertEquals("3", SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.first()).get("target").value)
        assertEquals(TextSendOutcome.Accepted, textResult.get())
    }

    @Test fun closeInterruptsRegisteredTextWaitWithoutASecondSubmission() {
        val entered = CountDownLatch(1)
        socket.onWrite = { entered.countDown() }
        val outcome = AtomicReference<TextSendOutcome>()
        val sender = Thread { outcome.set(adapter.sendTextMessage(TextTarget.Channel(1), "once", 5_000) { true }) }
        sender.start()
        assertTrue(entered.await(1, SECONDS))
        socket.closeTransport()
        sender.join(1_000)
        assertFalse(sender.isAlive)
        assertTrue(socket.awaitOperationsStopped(1_000))
        assertEquals(TextSendOutcome.Unknown, outcome.get())
        assertEquals(1, socket.writes.size)
    }

    @Test fun queuedWorkerRechecksContextBeforeSubmittingAfterChannelMove() {
        val moveEntered = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)
        socket.onWrite = {
            moveEntered.countDown()
            check(releaseMove.await(1, SECONDS))
        }
        val joinFailure = AtomicReference<Throwable?>()
        val joining = Thread { runCatching { adapter.joinChannel(5, "") }.onFailure { joinFailure.set(it) } }
        joining.start()
        assertTrue(moveEntered.await(1, SECONDS))
        val valid = AtomicBoolean(true)
        val checked = CountDownLatch(1)
        val result = AtomicReference<TextSendOutcome>()
        val sender =
            Thread {
                result.set(
                    adapter.sendTextMessage(TextTarget.Channel(3), "old channel", 1_000) {
                        checked.countDown()
                        valid.get()
                    },
                )
            }
        sender.start()
        assertTrue(checked.await(1, SECONDS))
        valid.set(false)
        releaseMove.countDown()
        sender.join(1_000)
        reply(0)
        joining.join(1_000)
        assertFalse(sender.isAlive)
        assertFalse(joining.isAlive)
        assertNull(joinFailure.get())
        assertTrue(result.get() is TextSendOutcome.NotSent)
        assertEquals(1, socket.writes.size)
    }

    @Test fun expirationBeforeClaimPreventsLateSubmissionEvenIfValidationIgnoresInterrupt() {
        val calls = AtomicInteger()
        val validationEntered = CountDownLatch(1)
        val outcome =
            adapter.sendTextMessage(TextTarget.Channel(1), "must not send", 100) {
                if (calls.incrementAndGet() > 1) {
                    validationEntered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } catch (_: InterruptedException) {
                        // Model a validator returning after its caller expired.
                    }
                }
                true
            }
        assertTrue(validationEntered.await(1, SECONDS))
        assertEquals(TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED), outcome)
        assertTrue(socket.awaitOperationsStopped(1_000))
        assertTrue(socket.writes.isEmpty())
    }

    @Test fun waitingTextCannotHoldBoundedDisconnectBudget() {
        val entered = CountDownLatch(1)
        socket.onWrite = { entered.countDown() }
        val outcome = AtomicReference<TextSendOutcome>()
        val sender =
            Thread {
                outcome.set(adapter.sendTextMessage(TextTarget.Channel(1), "pending", 5_000) { true })
            }
        sender.start()
        assertTrue(entered.await(1, SECONDS))
        val started = System.nanoTime()
        assertEquals(DisconnectOutcome.TIMED_OUT, adapter.disconnectBounded(300, "fixture exit"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("disconnect exceeded its bounded allowance", elapsedMs < 500)
        sender.join(1_000)
        assertFalse(sender.isAlive)
        assertTrue(socket.awaitOperationsStopped(0))
        assertEquals(TextSendOutcome.Unknown, outcome.get())
        assertEquals(1, socket.writes.count { it.startsWith("sendtextmessage") })
    }

    @Test fun privateTargetUsesModeOneClientIdAndSameWhitespaceEncoding() {
        socket.onWrite = { reply(0) }
        val body = "  私信😀 | \\  "
        val target = TextTarget.Private(18, "private-uid")
        assertEquals(TextSendOutcome.Accepted, adapter.sendTextMessage(target, body, 1_000) { true })
        val command = SingleCommand.parse(ProtocolRole.CLIENT, socket.writes.single())
        assertEquals("1", command.get("targetmode").value)
        assertEquals("18", command.get("target").value)
        assertEquals(body, command.get("msg").value)
        assertFalse(target.toString().contains("private-uid"))
    }

    private fun reply(code: Int) {
        socket.process(socket, SingleCommand.parse(ProtocolRole.SERVER, "error id=$code msg=private-error"))
    }

    private class CapturingSocket : StateAwareTeamspeakClientSocket() {
        val writes = CopyOnWriteArrayList<String>()
        val moveEnqueued = CountDownLatch(1)

        override fun executeCommand(command: Command): CommandResponse<Iterable<SingleCommand>> {
            val response = super.executeCommand(command)
            if (command.name == "clientmove") moveEnqueued.countDown()
            return response
        }

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
