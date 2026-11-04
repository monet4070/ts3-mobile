package io.github.ts3mobile.protocol

import com.github.manevolent.ts3j.command.CommandException
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Shared bounded claim, FIFO submission and interruption rules for session commands. */
internal class BoundedSessionCommands(
    private val socket: LocalTeamspeakClientSocket,
    private val operation: (() -> Unit) -> Unit,
    private val submissionLock: ReentrantLock,
) {
    private val workerActive = AtomicBoolean(false)

    fun execute(
        command: SingleCommand,
        timeoutMs: Long,
        canSubmit: () -> Boolean,
        onResponse: (Iterable<SingleCommand>) -> Unit = {},
    ): TextSendOutcome {
        if (timeoutMs <= 0 || !canSubmit()) return TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED)
        if (!workerActive.compareAndSet(false, true)) return TextSendOutcome.NotSent(TextNotSentReason.BUSY)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val completed = CountDownLatch(1)
        val submission = AtomicReference(Submission.READY)
        val result = AtomicReference<TextSendOutcome>(TextSendOutcome.Unknown)
        val worker =
            Thread({
                try {
                    operation {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0 || !submissionLock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                            result.set(TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED))
                            return@operation
                        }
                        val response =
                            try {
                                if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadline || !canSubmit()) {
                                    result.set(TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED))
                                    return@operation
                                }
                                // From this boundary the upstream queue may own the command.
                                if (!submission.compareAndSet(Submission.READY, Submission.CLAIMED)) {
                                    result.set(TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED))
                                    return@operation
                                }
                                socket.executeCommand(command)
                            } finally {
                                submissionLock.unlock()
                            }
                        val waitMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (waitMs > 0) {
                            onResponse(response.get(waitMs))
                            result.set(TextSendOutcome.Accepted)
                        }
                    }
                } catch (error: CommandException) {
                    result.set(TextSendOutcome.Rejected(classifyTextRejection(error.errorId), error.errorId))
                } catch (_: Exception) {
                    result.set(
                        if (submission.get() == Submission.CLAIMED) {
                            TextSendOutcome.Unknown
                        } else {
                            TextSendOutcome.NotSent(
                                TextNotSentReason.CONTEXT_CHANGED,
                            )
                        },
                    )
                } finally {
                    workerActive.set(false)
                    completed.countDown()
                }
            }, "TS3J/TextCommand").apply { isDaemon = true }
        try {
            worker.start()
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            if (!completed.await(remaining, TimeUnit.NANOSECONDS)) {
                return if (submission.compareAndSet(Submission.READY, Submission.ABANDONED) || submission.get() == Submission.ABANDONED) {
                    TextSendOutcome.NotSent(TextNotSentReason.CONTEXT_CHANGED)
                } else {
                    TextSendOutcome.Unknown
                }
            }
            return result.get()
        } finally {
            // An expired/cancelled caller must prevent an unclaimed submission.
            submission.compareAndSet(Submission.READY, Submission.ABANDONED)
            // Interrupts blocking ACK/response waits; queued commands are not revoked.
            if (worker.isAlive) worker.interrupt()
        }
    }

    private enum class Submission { READY, CLAIMED, ABANDONED }
}
