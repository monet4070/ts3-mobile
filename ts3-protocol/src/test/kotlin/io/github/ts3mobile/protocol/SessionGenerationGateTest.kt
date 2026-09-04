package io.github.ts3mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.AtomicBoolean

class SessionGenerationGateTest {
    @Test
    fun aNewGenerationRejectsCallbacksFromThePreviousSession() {
        val gate = SessionGenerationGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
        assertNull(gate.withCurrent(first) { "stale" })
        assertEquals("current", gate.withCurrent(second) { "current" })
    }

    @Test
    fun invalidationRejectsCallbacksWhenNoSocketRemains() {
        val gate = SessionGenerationGate()
        val token = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(token))
        assertNull(gate.withCurrent(token) { Unit })
    }

    @Test
    fun replacementActionSeesItsNewGenerationAtomically() {
        val gate = SessionGenerationGate()

        val token =
            gate.begin { replacementToken ->
                assertTrue(gate.isCurrent(replacementToken))
                replacementToken
            }

        assertTrue(gate.isCurrent(token))
    }

    @Test
    fun replacementWaitsForCurrentMutationToFinish() {
        val gate = SessionGenerationGate()
        val token = gate.begin()
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val mutationThread =
            Thread {
                gate.withCurrent(token) {
                    mutationEntered.countDown()
                    releaseMutation.await()
                }
            }
        val replacementThread =
            Thread {
                replacementStarted.countDown()
                gate.begin()
                replacementFinished.countDown()
            }

        mutationThread.start()
        assertTrue(mutationEntered.await(1_000, MILLISECONDS))
        replacementThread.start()
        assertTrue(replacementStarted.await(1_000, MILLISECONDS))
        assertFalse(replacementFinished.await(100, MILLISECONDS))

        releaseMutation.countDown()
        mutationThread.join(1_000)
        replacementThread.join(1_000)

        assertTrue(replacementFinished.await(0, MILLISECONDS))
    }

    @Test
    fun staleGenerationCannotCommitOwnerMutation() {
        val gate = SessionGenerationGate()
        val first = gate.begin()
        var owner = "first"
        val second =
            gate.begin { token ->
                owner = "second"
                token
            }
        val staleMutationRan = AtomicBoolean()

        val result =
            gate.withCurrent(first) {
                staleMutationRan.set(true)
                owner = "stale"
            }

        assertNull(result)
        assertFalse(staleMutationRan.get())
        assertEquals("second", owner)
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun concurrentStartsProduceUniqueTokensAndOneCurrentGeneration() {
        val gate = SessionGenerationGate()
        val tokens = ConcurrentLinkedQueue<Long>()
        val workers =
            List(8) {
                Thread {
                    repeat(250) { tokens += gate.begin() }
                }
            }

        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        assertEquals(2_000, tokens.size)
        assertEquals(2_000, tokens.toSet().size)
        assertEquals(1, tokens.count(gate::isCurrent))
    }
}
