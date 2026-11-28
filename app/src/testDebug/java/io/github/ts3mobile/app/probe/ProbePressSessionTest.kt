package io.github.ts3mobile.app.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbePressSessionTest {
    private var clock = 0L
    private val session = ProbePressSession { clock }

    @Test fun idleCannotCaptureAndTwoPressesDoNotShareOwnership() {
        assertNull(session.press())
        assertTrue(session.arm())
        assertFalse(session.arm())
        assertEquals(ProbePhase.READY, session.phase)
        val first = checkNotNull(session.press())
        assertTrue(session.started(first))
        assertTrue(session.release(first))
        assertNull(session.press())
        session.finished(first)
        val second = checkNotNull(session.press())
        assertNotEquals(first, second)
        assertFalse(session.release(first))
        session.finished(first)
        assertEquals(ProbePhase.STARTING, session.phase)
        assertTrue(session.started(second))
    }

    @Test fun releaseWhileOpeningRejectsTheLateStartAndWaitsForRecorderCleanup() {
        session.arm()
        val token = checkNotNull(session.press())
        assertTrue(session.release(token))
        assertFalse(session.started(token))
        assertNull(session.press())
        session.disarm()
        assertFalse(session.arm())
        session.finished(token)
        assertEquals(ProbePhase.IDLE, session.phase)
        assertTrue(session.arm())
    }

    @Test fun deadlinesAreMonotonicAndIdleTimeRestartsAfterRelease() {
        session.arm()
        clock = 299_999
        assertFalse(session.expired())
        val token = checkNotNull(session.press())
        clock += 60_000
        assertTrue(session.expired())
        assertFalse(session.started(token))
        assertTrue(session.release(token))
        session.finished(token)
        clock += 300_000
        assertTrue(session.expired())
        assertNull(session.press())
    }
}
