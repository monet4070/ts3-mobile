package io.github.ts3mobile.protocol

import io.github.ts3mobile.protocol.ConnectionAttemptGate.FailureDelivery.ALREADY_RECORDED
import io.github.ts3mobile.protocol.ConnectionAttemptGate.FailureDelivery.DEFER_UNTIL_CONNECTED_EMITTED
import io.github.ts3mobile.protocol.ConnectionAttemptGate.FailureDelivery.EMIT_NOW
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ConnectionAttemptGateTest {
    @Test
    fun failureBeforeConnectedClaimPreventsConnectedEmission() {
        val gate = ConnectionAttemptGate()
        val failure = IllegalStateException("connection failed")

        assertSame(EMIT_NOW, gate.recordFailure(failure))
        assertFalse(gate.beginConnectedEmission())
        assertSame(failure, gate.failureOrNull())
        assertSame(ALREADY_RECORDED, gate.recordFailure(IllegalStateException("later failure")))
    }

    @Test
    fun failureDuringConnectedEmissionIsDeliveredAfterConnected() {
        val gate = ConnectionAttemptGate()
        val failure = IllegalStateException("background failure")

        assertTrue(gate.beginConnectedEmission())
        assertSame(DEFER_UNTIL_CONNECTED_EMITTED, gate.recordFailure(failure))
        assertSame(failure, gate.completeConnectedEmission())
        assertSame(ALREADY_RECORDED, gate.recordFailure(IllegalStateException("later failure")))
    }

    @Test
    fun failureAfterConnectedEmissionIsDeliveredImmediately() {
        val gate = ConnectionAttemptGate()
        val failure = IllegalStateException("session failure")

        assertTrue(gate.beginConnectedEmission())
        assertNull(gate.completeConnectedEmission())
        assertSame(EMIT_NOW, gate.recordFailure(failure))
        assertSame(failure, gate.failureOrNull())
    }

    @Test
    fun concurrentFailureAndConnectedClaimHaveOneConsistentWinner() {
        repeat(250) {
            val gate = ConnectionAttemptGate()
            val barrier = CyclicBarrier(2)
            val connectedClaimed = AtomicBoolean()
            val failureDelivery = AtomicReference<ConnectionAttemptGate.FailureDelivery>()
            val failure = IllegalStateException("race")
            val connectedThread =
                Thread {
                    barrier.await()
                    connectedClaimed.set(gate.beginConnectedEmission())
                }
            val failureThread =
                Thread {
                    barrier.await()
                    failureDelivery.set(gate.recordFailure(failure))
                }

            connectedThread.start()
            failureThread.start()
            connectedThread.join()
            failureThread.join()

            if (connectedClaimed.get()) {
                assertSame(DEFER_UNTIL_CONNECTED_EMITTED, failureDelivery.get())
                assertSame(failure, gate.completeConnectedEmission())
            } else {
                assertSame(EMIT_NOW, failureDelivery.get())
                assertSame(failure, gate.failureOrNull())
            }
        }
    }
}
