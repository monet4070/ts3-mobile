package io.github.ts3mobile.audio.opus

internal class PlaybackTimeline(
    private val output: PlaybackOutput,
    private val reference: PlaybackReferenceSink? = null,
    private val receiveStats: ReceiveStats? = null,
    private val canWrite: () -> Boolean = { true },
) {
    enum class State { IDLE, PLAYING, RECOVERING, DRAINING }

    var state = State.IDLE
        private set
    var submittedFrames = 0L
        private set
    var playedFrames = 0L
        private set
    var drainTimeoutCount = 0L
        private set

    val acceptsPcm: Boolean get() = pending == null

    private var previousHead = 0L
    private var recoveryStarted = 0L
    private var drainStarted = 0L
    private var drainBudgetNanos = 0L
    private var pending: ShortArray? = null
    private var pendingOffset = 0
    private var writeStarted = 0L
    private var headOrigin = 0L
    private var startedAt = 0L
    private var pendingArrival: Long? = null

    fun start(now: Long = System.nanoTime()) {
        check(state == State.IDLE)
        submittedFrames = 0L
        playedFrames = 0L
        previousHead = output.playbackHead
        headOrigin = previousHead
        startedAt = now
        reference?.start()
        state = State.PLAYING
    }

    fun tick(
        samples: ShortArray?,
        now: Long,
        originalArrivalNanos: Long? = null,
    ) {
        if (state == State.IDLE) return
        updatePlayedFrames()
        if (samples != null && samples.isNotEmpty()) {
            check(pending == null) { "Playback still has an incomplete write" }
            pending = samples
            pendingOffset = 0
            writeStarted = now
            pendingArrival = originalArrivalNanos
            state = State.PLAYING
        }
        if (pending != null) {
            writePending(now)
            return
        }
        when (state) {
            State.PLAYING -> {
                recoveryStarted = now
                state = State.RECOVERING
            }
            State.RECOVERING -> {
                if (now - recoveryStarted < RECOVERY_NANOS) return
                drainStarted = now
                drainBudgetNanos =
                    ((submittedFrames - playedFrames) * NANOS_PER_SECOND / SAMPLE_RATE + DRAIN_MARGIN_NANOS)
                        .coerceAtMost(MAX_DRAIN_NANOS)
                state = State.DRAINING
                finishDrain(now)
            }
            State.DRAINING -> finishDrain(now)
            State.IDLE -> Unit
        }
    }

    fun discard() {
        state = State.IDLE
        pending = null
        reference?.discard()
        output.discard()
    }

    private fun updatePlayedFrames() {
        val currentHead = output.playbackHead
        val advanced = (currentHead - previousHead) and HEAD_MASK
        if (advanced > HEAD_MASK / 2) return
        previousHead = currentHead
        playedFrames = (playedFrames + advanced).coerceAtMost(submittedFrames)
    }

    private fun writePending(now: Long) {
        val samples = pending ?: return
        check(now - writeStarted < WRITE_TIMEOUT_NANOS) { "Audio output write timed out" }
        repeat(MAX_WRITES_PER_TICK) {
            if (!canWrite()) return
            val remaining = samples.size - pendingOffset
            val writeStart = System.nanoTime()
            val written = output.write(samples, pendingOffset, remaining)
            receiveStats?.peak(AudioReceivePeak.OUTPUT_WRITE_NS, System.nanoTime() - writeStart)
            check(written in 0..remaining) { "Audio output write failed: $written" }
            if (written == 0) {
                receiveStats?.count(AudioReceiveCounter.ZERO_WRITES)
                return
            }
            receiveStats?.count(AudioReceiveCounter.OUTPUT_SAMPLES, written.toLong())
            pendingArrival?.let { arrival -> receiveStats?.let { it.maximum(it.maxCombinedWaitNanos, now - arrival) } }
            if (reference != null) {
                val timestamp = output.sampleClock()
                val position = timestamp?.let { (it.framePosition - headOrigin) and HEAD_MASK }
                val measured =
                    if (timestamp != null && position != null && position <= submittedFrames + written &&
                        timestamp.nanoTime >= startedAt && now - timestamp.nanoTime in -10_000_000L..500_000_000L
                    ) {
                        AudioSampleClock(position, timestamp.nanoTime).timeAt(submittedFrames)
                    } else {
                        null
                    }
                val timing =
                    if (measured != null) {
                        RenderTiming(measured, AudioTimingQuality.MEASURED)
                    } else {
                        RenderTiming(now + (submittedFrames - playedFrames) * NANOS_PER_SECOND / SAMPLE_RATE, AudioTimingQuality.ESTIMATED)
                    }
                reference.accepted(samples, pendingOffset, written, submittedFrames, now, timing)
            }
            pendingOffset += written
            submittedFrames += written
            receiveStats?.peak(AudioReceivePeak.OUTPUT_BACKLOG_SAMPLES, submittedFrames - playedFrames)
            if (pendingOffset == samples.size) {
                pending = null
                return
            }
        }
    }

    private fun finishDrain(now: Long) {
        if (playedFrames >= submittedFrames) {
            discard()
        } else if (now - drainStarted >= drainBudgetNanos) {
            drainTimeoutCount++
            receiveStats?.drainTimeouts?.incrementAndGet()
            discard()
            System.err.println("TS3_AUDIO: playback drain timed out; unplayed tail discarded")
        }
    }

    internal companion object {
        const val RECOVERY_NANOS = 60_000_000L
        const val MAX_DRAIN_NANOS = 500_000_000L
        const val WRITE_TIMEOUT_NANOS = 100_000_000L
        private const val DRAIN_MARGIN_NANOS = 100_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val SAMPLE_RATE = 48_000
        private const val HEAD_MASK = 0xffff_ffffL
        private const val MAX_WRITES_PER_TICK = 8
    }
}
