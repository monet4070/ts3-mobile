package io.github.ts3mobile.protocol

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Tracks only work owned by one socket, including writers waiting for its command monitor. */
internal class SocketOperations {
    private val lock = Object()
    private val operations = mutableMapOf<Thread, Operation>()
    private var stopped = false

    fun <T> run(block: () -> T): T {
        val thread = Thread.currentThread()
        synchronized(lock) {
            if (stopped) throw IOException("Socket closed")
            operations.getOrPut(thread) { Operation(thread.isInterrupted) }.depth++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                val operation = checkNotNull(operations[thread])
                operation.depth--
                if (operation.depth == 0) {
                    operations.remove(thread)
                    // Do not leave our cancellation flag on a reusable IO worker.
                    // An interrupt already present on entry belongs to its caller.
                    if (operation.interruptedByStop && !operation.interruptedOnEntry) Thread.interrupted()
                    lock.notifyAll()
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            operations.forEach { (thread, operation) ->
                if (thread !== Thread.currentThread()) {
                    operation.interruptedByStop = true
                    thread.interrupt()
                }
            }
        }
    }

    fun awaitStopped(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        synchronized(lock) {
            while (operations.isNotEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                lock.wait(millis, (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt())
            }
            return true
        }
    }

    private class Operation(val interruptedOnEntry: Boolean) {
        var depth = 0
        var interruptedByStop = false
    }
}
