package io.github.ts3mobile.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Reason sent to the server when the client leaves without a user-visible message. */
internal const val DEFAULT_DISCONNECT_REASON = "Client disconnected"

/**
 * Result of a bounded client disconnect.
 *
 * The value reports what was actually observed, so it never turns a local
 * cleanup into a server-confirmed exit: [SERVER_CONFIRMED] requires this
 * session's own leave notification, and a lost acknowledgement or a local
 * close is reported as [TIMED_OUT] instead.
 */
enum class DisconnectOutcome {
    /** The server confirmed this session's own leave inside the budget. */
    SERVER_CONFIRMED,

    /** There was no established session to leave, so no command was sent. */
    NO_ACTIVE_SESSION,

    /** The budget elapsed before the server confirmed this session's leave. */
    TIMED_OUT,

    /** Sending the leave command failed before any confirmation could arrive. */
    FAILED,

    /** This transport cannot leave a session gracefully. */
    UNSUPPORTED,
}

/**
 * The transport operations a bounded disconnect needs.
 *
 * The seam exists so the deadline, cancellation and thread-reclamation behavior
 * can be exercised against a target that blocks, fails or confirms on demand,
 * without opening a socket or reaching into the fixed ts3j jar.
 */
internal interface BoundedDisconnectTarget {
    /** True while the transport still reports an established session. */
    val isSessionOpen: Boolean

    /** Sends one clientdisconnect through the transport's reliable command path. */
    fun sendClientDisconnect(
        reason: String,
        onRequestSent: () -> Unit,
    )

    /** Waits up to [timeoutMs] for this session's own leave notification. */
    fun awaitSelfLeaveNotification(timeoutMs: Long): Boolean

    /**
     * Ends the transport so a blocked send fails instead of waiting out its own
     * retransmission budget. Implementations must not wait for the transport's
     * command locks here; they may fail when the transport is already gone, and
     * the caller tolerates that.
     */
    fun closeTransport()

    /** Marks the transport disconnected so its pending retries stop. */
    fun markSessionDisconnected()

    fun awaitOperationsStopped(timeoutMs: Long): Boolean = true
}

/** One deadline covers confirmation and reclamation; cleanup reserves part of that budget. */
internal class BoundedClientDisconnect(
    private val clock: () -> Long = System::nanoTime,
    private val startWorker: (String, Runnable) -> Thread = { name, runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
            start()
        }
    },
) {
    fun run(
        target: BoundedDisconnectTarget,
        reason: String,
        timeoutMs: Long,
        onRequestSent: () -> Unit = {},
    ): DisconnectOutcome {
        require(timeoutMs > 0L) { "Disconnect timeout must be positive" }
        if (!target.isSessionOpen) return DisconnectOutcome.NO_ACTIVE_SESSION
        val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val deadline = clock() + budget
        val workDeadline = deadline - minOf(budget / 4, TimeUnit.MILLISECONDS.toNanos(250))
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val sender =
            startWorker("TS3J/BoundedDisconnectSender") {
                try {
                    target.sendClientDisconnect(reason, onRequestSent)
                } catch (
                    error: Throwable,
                ) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }
        var reclaimed = false
        val outcome =
            try {
                awaitOutcome(target, workDeadline, finished, failure)
            } finally {
                // An interrupted confirmation wait must still release the captured socket.
                reclaimed = release(target, sender, deadline)
            }
        return if (reclaimed) outcome else DisconnectOutcome.FAILED
    }

    private fun awaitOutcome(
        target: BoundedDisconnectTarget,
        deadline: Long,
        finished: CountDownLatch,
        failure: AtomicReference<Throwable?>,
    ): DisconnectOutcome {
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return DisconnectOutcome.TIMED_OUT
            val slice = minOf(25L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
            if (target.awaitSelfLeaveNotification(slice)) return DisconnectOutcome.SERVER_CONFIRMED
            if (failure.get() != null) return DisconnectOutcome.FAILED
            if (finished.count == 0L) {
                val wait = TimeUnit.NANOSECONDS.toMillis(deadline - clock()).coerceAtLeast(0)
                return if (wait > 0 && target.awaitSelfLeaveNotification(wait)) {
                    DisconnectOutcome.SERVER_CONFIRMED
                } else {
                    DisconnectOutcome.TIMED_OUT
                }
            }
        }
    }

    private fun release(
        target: BoundedDisconnectTarget,
        sender: Thread,
        deadline: Long,
    ): Boolean {
        val releaser =
            startWorker("TS3J/BoundedDisconnectReleaser") {
                releaseStep { target.closeTransport() }
                releaseStep { target.markSessionDisconnected() }
            }
        sender.interrupt()
        var interrupted = false
        for (thread in listOf(releaser, sender)) {
            while (thread.isAlive) {
                val remaining = deadline - clock()
                if (remaining <= 0) break
                try {
                    val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                    val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                    // join(0) is unbounded; only a positive duration reaches this call.
                    thread.join(millis, nanos)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - clock()).coerceAtLeast(0))
        val operationsStopped =
            try {
                target.awaitOperationsStopped(remaining)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
        if (interrupted) Thread.currentThread().interrupt()
        return !releaser.isAlive && !sender.isAlive && operationsStopped
    }

    private fun releaseStep(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            System.err.println("TS3_DIAG: exit cleanup failed; types=${error.sanitizedFailureTypes()}")
        }
    }
}
