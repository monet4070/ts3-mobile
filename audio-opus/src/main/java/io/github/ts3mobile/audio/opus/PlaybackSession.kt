package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.sanitizedFailureTypes

enum class PlaybackStatus { STOPPED, BUFFERING, PLAYING, RETRYING, FAILED }

/** Worker-owned; generation checks prevent stale writes and retirement gates replacement. */
internal class PlaybackSession(
    private val input: VoiceReceiveQueue,
    private val streams: PlaybackStreams,
    private val createOutput: () -> PlaybackOutput,
    private val reference: PlaybackReferenceSink? = null,
    private val onStatus: (PlaybackStatus) -> Unit = {},
    private val canWrite: () -> Boolean = { true },
    private val prepareOutputRoute: () -> Unit = {},
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
    private var outputFailures = 0
    private var healthyFrames = 0L
    private var idleSince: Long? = null
    private var unstartedReadySince: Long? = null
    val retired: Boolean get() = closed && output == null
    private val stats = input.budget.stats

    fun tick(now: Long) {
        if (!canWrite()) return
        if (closed || status == PlaybackStatus.FAILED) {
            input.clear(canWrite)
            return
        }
        if (now < retryAt) {
            input.clear(canWrite)
            return
        }
        try {
            if (output?.routeReady == false) {
                unstartedReadySince = null
                streams.reset()
                input.clear(canWrite)
                if (started) {
                    check(retireOutput(OutputRetirement.ROUTE_UNAVAILABLE)) { "Previous audio output could not be released" }
                    status = PlaybackStatus.BUFFERING
                }
                return
            }
            idleSince?.let { previous ->
                val samples = ((now - previous).coerceAtLeast(0).coerceAtMost(1_000_000_000L) * 48_000 / 1_000_000_000L).toInt()
                streams.advanceSilence(samples)
                idleSince = now
            }
            streams.drainAvailable(ReceivePolicy.MAX_INPUT_PACKETS, now, canWrite)
            streams.prepare(now)
            if (!started) {
                if (!streams.readyToStart(now)) {
                    if (output != null) {
                        val since = unstartedReadySince ?: now.also { unstartedReadySince = it }
                        if (now - since >= PlaybackPolicy.UNSTARTED_READY_IDLE_NANOS) {
                            check(retireOutput(OutputRetirement.DRAINED)) { "Previous audio output could not be released" }
                            idleSince = now
                        }
                    } else {
                        unstartedReadySince = null
                    }
                    return
                }
                unstartedReadySince = null
                prepareOutputRoute()
                if (!canWrite()) return
                val currentOutput =
                    output ?: observeSlowOutput(AudioDiagnosticEventKind.OUTPUT_CREATE_SLOW_MS, createOutput).also {
                        output = it
                        stats.count(AudioReceiveCounter.OUTPUTS_CREATED)
                        stats.observer?.onEvent(AudioDiagnosticEventKind.OUTPUT_CREATED)
                    }
                if (!canWrite()) return
                observeSlowOutput(AudioDiagnosticEventKind.OUTPUT_START_SLOW_MS, currentOutput::start)
                if (!currentOutput.routeReady || !canWrite()) {
                    streams.reset()
                    input.clear(canWrite)
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
                check(retireOutput(OutputRetirement.DRAINED)) { "Previous audio output could not be released" }
                idleSince = now
                status = PlaybackStatus.BUFFERING
            }
        } catch (failure: RuntimeException) {
            stats.outputFailures.incrementAndGet()
            outputFailures++
            healthyFrames = 0
            streams.reset()
            input.clear(canWrite)
            val released = retireOutput(OutputRetirement.OUTPUT_FAILURE)
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

    fun invalidateOutput(
        now: Long,
        profile: ReceiveDelayProfile? = null,
    ) {
        if (closed) return
        streams.reset(profile)
        resetOutput(now, 0)
        if (profile != null) stats.observer?.onEvent(AudioDiagnosticEventKind.RECEIVE_DELAY_PROFILE, profile.diagnosticCode)
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
        if (!retireOutput(OutputRetirement.INVALIDATED)) {
            stats.outputRecoveryExhausted.incrementAndGet()
            status = PlaybackStatus.FAILED
            return
        }
        outputFailures = 0
        healthyFrames = 0
        idleSince = null
        unstartedReadySince = null
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
        status = if (retireOutput(OutputRetirement.STOPPED)) PlaybackStatus.STOPPED else PlaybackStatus.FAILED
    }

    private inline fun <T> observeSlowOutput(
        kind: AudioDiagnosticEventKind,
        action: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            action()
        } finally {
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000L
            if (durationMs >= PlaybackPolicy.SLOW_OUTPUT_MS) stats.observer?.onEvent(kind, durationMs)
        }
    }

    private fun retireOutput(reason: OutputRetirement): Boolean {
        if (output != null) stats.observer?.onEvent(AudioDiagnosticEventKind.OUTPUT_RETIRED, reason.code)
        started = false
        unstartedReadySince = null
        cleanup("discard output") { timeline?.discard() }
        timeline = null
        val released =
            runCatching { output?.close() }.onFailure { error ->
                System.err.println("TS3_AUDIO: failed to close output: ${error.sanitizedFailureTypes()}")
            }.isSuccess
        if (released) output = null
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
    const val SLOW_OUTPUT_MS = 30L
    const val TICK_NANOS = 10_000_000L
    const val MIN_WAIT_NANOS = 1_000_000L
    const val MAX_CATCH_UP_NANOS = 50_000_000L
    const val UNSTARTED_READY_IDLE_NANOS = 500_000_000L
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
