package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class SessionGenerationTest {
    @Test
    fun aNewSessionInvalidatesCallbacksFromThePreviousSession() {
        val generation = SessionGeneration()
        val first = generation.beginSession()
        val second = generation.beginSession()

        assertFalse(generation.isActive(first))
        assertTrue(generation.isActive(second))
        assertFalse(generation.isDisconnectRequested)
    }

    @Test
    fun disconnectImmediatelyInvalidatesTheActiveSession() {
        val generation = SessionGeneration()
        val token = generation.beginSession()

        generation.invalidate()

        assertFalse(generation.isActive(token))
        assertTrue(generation.isDisconnectRequested)
    }

    @Test
    fun repeatedReconnectCyclesLeaveOnlyTheNewestGenerationActive() {
        val generation = SessionGeneration()
        val tokens = (1..100).map { generation.beginSession() }

        assertEquals(tokens.distinct(), tokens)
        assertTrue(generation.isActive(tokens.last()))
        assertTrue(tokens.dropLast(1).none(generation::isActive))
    }

    @Test
    fun concurrentStartsStillProduceUniqueGenerationsAndOneActiveSession() {
        val generation = SessionGeneration()
        val tokens = ConcurrentLinkedQueue<Long>()
        val workers =
            List(8) {
                Thread {
                    repeat(250) { tokens += generation.beginSession() }
                }
            }

        workers.forEach(Thread::start)
        workers.forEach(Thread::join)

        assertEquals(2_000, tokens.size)
        assertEquals(2_000, tokens.toSet().size)
        assertEquals(1, tokens.count(generation::isActive))
    }
}
