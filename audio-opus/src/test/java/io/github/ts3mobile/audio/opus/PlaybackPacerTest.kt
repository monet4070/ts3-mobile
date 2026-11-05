package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPacerTest {
    @Test
    fun ordinaryWorkDoesNotMoveTheMediaDeadline() {
        val pacer = PlaybackPacer()
        var now = ORIGIN + pacer.waitNanos(ORIGIN)
        pacer.completed(now)
        repeat(1000) {
            now += 2 * MS
            assertEquals(8 * MS, pacer.waitNanos(now))
            now += pacer.waitNanos(now)
            pacer.completed(now)
        }
        assertEquals(ORIGIN + MS + 10_000 * MS, now)
    }

    @Test
    fun shortSchedulerStallPreservesTheMediaDeadline() {
        val pacer = PlaybackPacer()
        var now = ORIGIN
        now += pacer.waitNanos(now)
        pacer.completed(now)
        now += pacer.waitNanos(now) + 25 * MS
        pacer.completed(now)

        repeat(2) {
            assertEquals(PlaybackPolicy.MIN_WAIT_NANOS, pacer.waitNanos(now))
            now += pacer.waitNanos(now)
            pacer.completed(now)
        }
        assertTrue(pacer.waitNanos(now) > PlaybackPolicy.MIN_WAIT_NANOS)
    }

    @Test
    fun repeatedShortStallsDoNotSlowTheTenMillisecondMediaClock() {
        val pacer = PlaybackPacer()
        var now = ORIGIN
        var ticks = 0
        while (now < ORIGIN + 10_000 * MS) {
            now += pacer.waitNanos(now)
            if (ticks % 10 == 9) now += 25 * MS
            pacer.completed(now)
            ticks++
        }

        assertTrue("Only $ticks media ticks in ten seconds", ticks >= 998)
    }

    @Test
    fun longStallsStillResetTheDeadlineAndLeaveABoundedWait() {
        val pacer = PlaybackPacer()
        var now = ORIGIN
        repeat(10) {
            now += 500 * MS
            pacer.completed(now)
            assertTrue(pacer.waitNanos(now) in PlaybackPolicy.MIN_WAIT_NANOS..PlaybackPolicy.TICK_NANOS)
            now += pacer.waitNanos(now)
            pacer.completed(now)
            assertTrue(pacer.waitNanos(now) > PlaybackPolicy.MIN_WAIT_NANOS)
        }
    }

    private companion object {
        const val MS = 1_000_000L
        const val ORIGIN = 1_000_000_000_000L
    }
}
