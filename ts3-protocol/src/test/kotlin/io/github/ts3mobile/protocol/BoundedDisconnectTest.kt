package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the bounded disconnect runner.
 *
 * The runner is driven against a target that blocks, fails or confirms on
 * demand, so the monotonic deadline, the release ordering and thread
 * reclamation are exercised without opening a socket and without reaching into
 * the fixed ts3j jar.
 */
class BoundedDisconnectTest {
    private val runner = BoundedClientDisconnect()

    @Test
    fun aSessionThatIsAlreadyGoneIsReportedWithoutSendingAnything() {
        val target = FakeTarget(isSessionOpen = false)

        val outcome = runner.run(target, REASON, TIMEOUT_MS)

        assertEquals(DisconnectOutcome.NO_ACTIVE_SESSION, outcome)
        assertEquals(emptyList<String>(), target.sentReasons)
        assertEquals(emptyList<String>(), target.releaseOrder)
        assertEquals(emptyList<Long>(), target.confirmationBudgets)
    }

    @Test
    fun aConfirmedLeaveIsReportedAsServerConfirmed() {
        val target = FakeTarget(confirm = { true })

        val outcome = runner.run(target, REASON, TIMEOUT_MS)

        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, outcome)
        assertEquals(listOf(REASON), target.sentReasons)
        // A confirmed session is still released here, so the sender cannot keep
        // waiting for an acknowledgement the server no longer sends.
        assertEquals(listOf("close", "mark"), target.releaseOrder)
        assertNoDisconnectThreadsRemain()
    }

    @Test
    fun aConfirmationThatArrivesBeforeTheSendReturnsIsReported() {
        val target =
            FakeTarget(
                send = { Thread.sleep(NEVER_FINISHING_SEND_MILLIS) },
                confirm = { true },
            )

        val startedAt = System.nanoTime()
        val outcome = runner.run(target, REASON, TIMEOUT_MS)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        // The server's own leave notification is the confirmation; the
        // acknowledgement of our command is not, so a lost acknowledgement must
        // not turn a leave the server already confirmed into a timeout.
        assertEquals(DisconnectOutcome.SERVER_CONFIRMED, outcome)
        assertTrue(
            "waited ${elapsedMillis}ms for a confirmation that had already arrived",
            elapsedMillis < TIMEOUT_MS,
        )
        assertEquals(listOf("close", "mark"), target.releaseOrder)
        assertTrue("the still-running sender was not interrupted", target.sendInterrupted)
        assertNoDisconnectThreadsRemain()
    }

    @Test
    fun aSendThatIsNeverConfirmedTimesOutAndReleasesTheTransport() {
        val target = FakeTarget(confirm = { waitForBudget(it) })

        val outcome = runner.run(target, REASON, TIMEOUT_MS)

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertEquals(listOf(REASON), target.sentReasons)
        // The transport is closed before the session is marked disconnected, so a
        // blocked command write fails instead of waiting out its retries.
        assertEquals(listOf("close", "mark"), target.releaseOrder)
        assertTrue(target.confirmationBudgets.isNotEmpty())
        assertTrue(target.confirmationBudgets.all { it in 1..TIMEOUT_MS })
        assertNoDisconnectThreadsRemain()
    }

    @Test
    fun aFailedSendIsReportedAsFailedAndReleasesTheTransport() {
        val target = FakeTarget(send = { throw IOException("transport is gone") })

        val startedAt = System.nanoTime()
        val outcome = runner.run(target, REASON, TIMEOUT_MS)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertEquals(DisconnectOutcome.FAILED, outcome)
        assertEquals(listOf(REASON), target.sentReasons)
        assertEquals(listOf("close", "mark"), target.releaseOrder)
        // A failed send can never be confirmed, so the call must not spend the
        // rest of the budget waiting for one.
        assertTrue("elapsed ${elapsedMillis}ms spent the whole budget", elapsedMillis < TIMEOUT_MS)
        assertNoDisconnectThreadsRemain()
    }

    @Test
    fun aBlockedSendIsAbandonedAtTheDeadlineAndItsThreadTerminates() {
        val sendEntered = CountDownLatch(1)
        val neverFinishes = CountDownLatch(1)
        val target =
            FakeTarget(
                send = {
                    sendEntered.countDown()
                    neverFinishes.await()
                },
            )

        val startedAt = System.nanoTime()
        val outcome = runner.run(target, REASON, TIMEOUT_MS)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertTrue(sendEntered.await(1, TimeUnit.SECONDS))
        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertTrue(
            "elapsed ${elapsedMillis}ms exceeded the budget",
            elapsedMillis <= TIMEOUT_MS + RELEASE_SLACK_MS,
        )
        assertEquals(listOf("close", "mark"), target.releaseOrder)
        assertTrue("the blocked sender was not interrupted", target.sendInterrupted)
        assertNoDisconnectThreadsRemain()
    }

    @Test
    fun theConfirmationWaitOnlyGetsTheBudgetLeftAfterTheSend() {
        val sendMillis = 100L
        val target =
            FakeTarget(
                send = { Thread.sleep(sendMillis) },
                confirm = { waitForBudget(it) },
            )

        val startedAt = System.nanoTime()
        val outcome = runner.run(target, REASON, TIMEOUT_MS)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        val confirmationBudget = target.confirmationBudgets.last()
        assertTrue(
            "confirmation budget ${confirmationBudget}ms did not shrink",
            confirmationBudget < TIMEOUT_MS,
        )
        assertTrue(elapsedMillis <= TIMEOUT_MS + RELEASE_SLACK_MS)
    }

    @Test
    fun aTransportThatCannotBeClosedIsStillMarkedDisconnected() {
        val target =
            FakeTarget(
                confirm = { waitForBudget(it) },
                close = { throw IOException("already closed") },
            )

        val outcome = runner.run(target, REASON, TIMEOUT_MS)

        assertEquals(DisconnectOutcome.TIMED_OUT, outcome)
        assertEquals(listOf("close", "mark"), target.releaseOrder)
        assertNoDisconnectThreadsRemain()
    }

    @Test
    fun aNonPositiveBudgetIsRejectedBeforeTouchingTheTransport() {
        val target = FakeTarget()

        assertThrows(IllegalArgumentException::class.java) { runner.run(target, REASON, 0L) }

        assertEquals(emptyList<String>(), target.sentReasons)
        assertEquals(emptyList<String>(), target.releaseOrder)
    }

    @Test
    fun anInterruptedConfirmationWaitStillReclaimsTheSender() {
        val target =
            FakeTarget(
                send = { CountDownLatch(1).await() },
                confirm = { throw InterruptedException("cancelled wait") },
            )

        assertThrows(InterruptedException::class.java) { runner.run(target, REASON, TIMEOUT_MS) }

        assertEquals(listOf("close", "mark"), target.releaseOrder)
        assertNoDisconnectThreadsRemain()
    }

    private class FakeTarget(
        override val isSessionOpen: Boolean = true,
        private val send: (String) -> Unit = { },
        private val confirm: (Long) -> Boolean = { waitForBudget(it) },
        private val close: () -> Unit = { },
        private val mark: () -> Unit = { },
    ) : BoundedDisconnectTarget {
        val sentReasons = CopyOnWriteArrayList<String>()
        val releaseOrder = CopyOnWriteArrayList<String>()
        val confirmationBudgets = CopyOnWriteArrayList<Long>()

        @Volatile
        var sendInterrupted = false

        override fun sendClientDisconnect(
            reason: String,
            onRequestSent: () -> Unit,
        ) {
            sentReasons += reason
            try {
                send(reason)
            } catch (_: InterruptedException) {
                sendInterrupted = true
                throw InterruptedException("sender interrupted")
            }
        }

        override fun awaitSelfLeaveNotification(timeoutMs: Long): Boolean {
            confirmationBudgets += timeoutMs
            return confirm(timeoutMs)
        }

        override fun closeTransport() {
            releaseOrder += "close"
            close()
        }

        override fun markSessionDisconnected() {
            releaseOrder += "mark"
            mark()
        }
    }

    private fun assertNoDisconnectThreadsRemain() {
        val deadline = System.nanoTime() + THREAD_STOP_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        while (true) {
            val running = disconnectThreadNames()
            if (running.isEmpty()) return
            if (System.nanoTime() > deadline) {
                fail("bounded disconnect threads still running: $running")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun disconnectThreadNames(): List<String> =
        Thread.getAllStackTraces().keys
            .map { it.name }
            .filter { it.startsWith(DISCONNECT_THREAD_PREFIX) }

    private companion object {
        const val REASON = "Task removed"
        const val TIMEOUT_MS = 250L
        const val RELEASE_SLACK_MS = 400L
        const val THREAD_STOP_TIMEOUT_MILLIS = 5_000L
        const val POLL_MILLIS = 5L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val NEVER_FINISHING_SEND_MILLIS = 60_000L
        const val DISCONNECT_THREAD_PREFIX = "TS3J/BoundedDisconnect"
    }
}

/** Sleeps out the granted budget, so a timeout costs what the runner asked for. */
private fun waitForBudget(timeoutMs: Long): Boolean {
    Thread.sleep(timeoutMs.coerceAtMost(MAX_SIMULATED_WAIT_MILLIS))
    return false
}

private const val MAX_SIMULATED_WAIT_MILLIS = 5_000L
