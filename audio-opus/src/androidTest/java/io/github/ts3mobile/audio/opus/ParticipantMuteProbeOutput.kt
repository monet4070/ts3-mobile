package io.github.ts3mobile.audio.opus

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal enum class MuteProbePoint { PENDING, PARTIAL, BLOCKED, FULL_BARRIER, ACCEPT }

/** Synthetic output only: no AudioTrack or hardware samples. */
internal class ParticipantMuteProbeOutput(val point: MuteProbePoint, private val onFirstWrite: () -> Unit = {}) : PlaybackOutput {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val writes = AtomicInteger()
    var accepted = 0L
        private set
    var firstWriteAt = 0L
        private set
    private var headReads = 0
    private var parked = false

    override val playbackHead: Long get() {
        headReads++
        if (point == MuteProbePoint.PENDING && headReads == 3) park()
        return 0L
    }

    override fun start() = Unit

    override fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int {
        val call = writes.incrementAndGet()
        if (call == 1) {
            firstWriteAt = System.nanoTime()
            onFirstWrite()
        }
        val written =
            when {
                point == MuteProbePoint.PENDING -> 0
                point == MuteProbePoint.PARTIAL && call == 1 -> minOf(120, count)
                point == MuteProbePoint.PARTIAL -> {
                    park()
                    0
                }
                point == MuteProbePoint.BLOCKED || point == MuteProbePoint.FULL_BARRIER -> {
                    park()
                    count
                }
                else -> count
            }
        accepted += written
        if (point == MuteProbePoint.ACCEPT) entered.countDown()
        return written
    }

    override fun discard() = Unit

    override fun close() {
        closed.countDown()
    }

    private fun park() {
        if (parked) return
        parked = true
        entered.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (release.count > 0 && System.nanoTime() < deadline) {
            try {
                release.await(20, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        check(release.count == 0L) { "Synthetic output barrier timed out" }
    }
}
