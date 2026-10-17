package io.github.ts3mobile.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SocketOperationsTest {
    @Test
    fun stoppingANestedOperationWakesItWithoutLeakingAnInterruptIntoTheNextTask() {
        val operations = SocketOperations()
        val entered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor.submit<Boolean> {
                    try {
                        operations.run {
                            operations.run {
                                entered.countDown()
                                CountDownLatch(1).await()
                            }
                        }
                    } catch (_: InterruptedException) {
                        // An IO boundary observes cancellation, then leaves its owned operation.
                    }
                    Thread.currentThread().isInterrupted
                }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            operations.stop()
            assertTrue(operations.awaitStopped(500))
            assertFalse(result.get(1, TimeUnit.SECONDS))
            assertFalse(executor.submit<Boolean> { Thread.currentThread().isInterrupted }.get(1, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { operations.run { error("must not execute") } }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun anInterruptAlreadyOwnedByTheCallerIsPreserved() {
        val operations = SocketOperations()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(
                executor.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    operations.run { operations.stop() }
                    Thread.currentThread().isInterrupted.also { Thread.interrupted() }
                }.get(1, TimeUnit.SECONDS),
            )
            assertTrue(operations.awaitStopped(0))
        } finally {
            executor.shutdownNow()
        }
    }
}
