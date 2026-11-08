package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.sanitizedFailureTypes

enum class PlaybackStatus { STOPPED, BUFFERING, PLAYING, RETRYING, FAILED }

/** Worker-owned; generation checks prevent stale writes and retirement gates replacement. */
internal class PlaybackSession(
    private val input: VoiceReceiveQueue,
    private val streams: PlaybackStreams,
    private val createOutput: () -> PlaybackOutput,
    private val requestFocus: () -> Boolean,
    private val abandonFocus: () -> Unit,
    private val reference: PlaybackReferenceSink? = null,
    private val onStatus: (PlaybackStatus) -> Unit = {},
    private val canWrite: () -> Boolean = { true },
) : AutoCloseable {
    @Volatile var output: PlaybackOutput? = null
        private set
    var status = PlaybackStatus.BUFFERING
        private set(value) {
            if (field != value) {
                field = value
                onStatus(value)
            }
        }
    private var timeline: PlaybackTimeline? = null
    private var closed = false
    private var started = false
    private var retryAt = 0L
    private var focusDenials = 0
    private var outputFailures = 0
    private var healthyFrames = 0L
    private var idleSince: Long? = null
    val retired: Boolean get() = closed && output == null
    private val stats = input.budget.stats

    fun tick(now: Long) {
        if (!canWrite()) return
        if (closed || status == PlaybackStatus.FAILED) {
            input.clear()
            return
        }
        if (now < retryAt) {
            input.clear()
            return
        }
        try {
            if (output?.routeReady == false) {
                streams.reset()
                input.clear()
                if (started) {
                    check(retireOutput()) { "Previous audio output could not be released" }
                    status = PlaybackStatus.BUFFERING
                }
                return
            }
            idleSince?.let { previous ->
                val samples = ((now - previous).coerceAtLeast(0).coerceAtMost(1_000_000_000L) * 48_000 / 1_000_000_000L).toInt()
                streams.advanceSilence(samples)
                idleSince = now
            }
            streams.drainAvailable(ReceivePolicy.MAX_INPUT_PACKETS, now)
            streams.prepare(now)
            if (!started) {
                if (!streams.readyToStart(now)) return
                if (!requestFocus()) {
                    stats.focusDenials.incrementAndGet()
                    focusDenials = minOf(focusDenials + 1, PlaybackPolicy.MAX_BACKOFF_EXPONENT)
                    retryAt = now + PlaybackPolicy.backoff(focusDenials)
                    streams.reset()
                    input.clear()
                    abandonFocus()
                    status = PlaybackStatus.RETRYING
                    return
                }
                focusDenials = 0
                val currentOutput = output ?: createOutput().also { output = it }
                if (!canWrite()) return
                currentOutput.start()
                if (!currentOutput.routeReady || !canWrite()) {
                    streams.reset()
                    input.clear()
                    return
                }
                val currentTimeline = PlaybackTimeline(currentOutput, reference, stats, canWrite).also { timeline = it }
                currentTimeline.start(now)
                started = true
                idleSince = null
                status = PlaybackStatus.PLAYING
            }
            val current = checkNotNull(timeline)
            val before = current.submittedFrames
            val mixed = if (current.acceptsPcm) streams.mix(now) else null
            current.tick(mixed, now, streams.oldestMixedArrival)
            healthyFrames += current.submittedFrames - before
            if (healthyFrames >= PlaybackPolicy.HEALTHY_FRAMES) {
                if (outputFailures > 0) stats.outputRecoveries.incrementAndGet()
                outputFailures = 0
            }
            if (current.state == PlaybackTimeline.State.IDLE) {
                streams.finishTalkspurts()
                check(retireOutput()) { "Previous audio output could not be released" }
                idleSince = now
                status = PlaybackStatus.BUFFERING
            }
        } catch (failure: RuntimeException) {
            stats.outputFailures.incrementAndGet()
            outputFailures++
            healthyFrames = 0
            streams.reset()
            input.clear()
            val released = retireOutput()
            if (!released || outputFailures >= PlaybackPolicy.MAX_OUTPUT_FAILURES) {
                stats.outputRecoveryExhausted.incrementAndGet()
                status = PlaybackStatus.FAILED
                System.err.println("TS3_AUDIO: playback recovery exhausted: ${failure.sanitizedFailureTypes()}")
            } else {
                retryAt = now + PlaybackPolicy.backoff(outputFailures)
                status = PlaybackStatus.RETRYING
                System.err.println("TS3_AUDIO: playback output recovery scheduled: ${failure.sanitizedFailureTypes()}")
            }
        }
    }

    fun invalidate(
        now: Long,
        focusInterrupted: Boolean = false,
    ) {
        if (closed) return
        streams.reset()
        input.clear()
        resetOutput(now, if (focusInterrupted) now + PlaybackPolicy.INITIAL_BACKOFF_NANOS else 0)
    }

    fun invalidateParticipants(
        clients: Set<Int>,
        now: Long,
    ) {
        if (closed) return
        streams.invalidateParticipants(clients)
        // Producers already removed old input. Do not delete newly admitted post-unmute packets.
        resetOutput(now, retryAt)
    }

    private fun resetOutput(
        now: Long,
        nextRetryAt: Long,
    ) {
        if (!retireOutput()) {
            stats.outputRecoveryExhausted.incrementAndGet()
            status = PlaybackStatus.FAILED
            return
        }
        outputFailures = 0
        healthyFrames = 0
        idleSince = null
        retryAt = nextRetryAt
        status = if (now < retryAt) PlaybackStatus.RETRYING else PlaybackStatus.BUFFERING
    }

    override fun close() {
        if (!closed) {
            closed = true
            cleanup("close streams") { streams.close() }
            input.clear()
            System.err.println(stats.summaryLine())
        }
        status = if (retireOutput()) PlaybackStatus.STOPPED else PlaybackStatus.FAILED
    }

    private fun retireOutput(): Boolean {
        started = false
        cleanup("discard output") { timeline?.discard() }
        timeline = null
        val released =
            runCatching { output?.close() }.onFailure { error ->
                System.err.println("TS3_AUDIO: failed to close output: ${error.sanitizedFailureTypes()}")
            }.isSuccess
        if (released) output = null
        cleanup("abandon focus", abandonFocus)
        return released
    }

    private fun cleanup(
        operation: String,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            System.err.println("TS3_AUDIO: failed to $operation: ${error.sanitizedFailureTypes()}")
        }
    }
}

internal object PlaybackPolicy {
    const val TICK_NANOS = 10_000_000L
    const val MIN_WAIT_NANOS = 1_000_000L
    const val MAX_CATCH_UP_NANOS = 50_000_000L
    const val INITIAL_BACKOFF_NANOS = 250_000_000L
    const val MAX_BACKOFF_EXPONENT = 4
    const val MAX_OUTPUT_FAILURES = 3
    const val HEALTHY_FRAMES = 48_000L

    fun backoff(attempt: Int): Long = INITIAL_BACKOFF_NANOS * (1L shl (attempt.coerceIn(1, MAX_BACKOFF_EXPONENT) - 1))
}

internal class PlaybackPacer {
    private var nextTick: Long? = null

    fun waitNanos(now: Long): Long =
        nextTick?.let { (it - now).coerceIn(PlaybackPolicy.MIN_WAIT_NANOS, PlaybackPolicy.TICK_NANOS) }
            ?: PlaybackPolicy.MIN_WAIT_NANOS

    fun completed(now: Long) {
        val deadline = nextTick
        nextTick =
            if (deadline == null || now - deadline > PlaybackPolicy.MAX_CATCH_UP_NANOS) {
                now + PlaybackPolicy.TICK_NANOS
            } else {
                deadline + PlaybackPolicy.TICK_NANOS
            }
    }
}
