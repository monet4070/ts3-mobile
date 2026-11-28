package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingPttPressesTest {
    private var now = 0L
    private var target: FloatingPttTarget? = FloatingPttTarget("connection", 9)
    private val presses = FloatingPttPresses({ now }, { target })

    @Test fun idleDeadlineAndHardPressDeadlineNeverRenewThemselves() {
        assertTrue(presses.arm())
        now = 299_999L
        val first = checkNotNull(presses.begin())
        assertNull(presses.begin())
        now += 60_000L
        assertFalse(presses.isValid)
        assertNull(presses.capture)
        presses.release(first) { }
        assertFalse(presses.isArmed)
        assertTrue(presses.arm())
        now += 300_000L
        assertFalse(presses.isValid)
        assertNull(presses.begin())
    }

    @Test fun staleUpAndChannelOrConnectionChangesCannotAuthorizeCapture() {
        assertTrue(presses.arm())
        val first = checkNotNull(presses.begin())
        presses.release(first) { }
        val second = checkNotNull(presses.begin())
        assertNotEquals(first, second)
        presses.release(first) { error("old token stopped new press") }
        assertEquals(second, presses.capture?.token)
        target = FloatingPttTarget("connection", 10)
        assertNull(presses.capture)
        assertTrue(presses.disarm())
        assertTrue(presses.arm())
        target = FloatingPttTarget("replacement", 10)
        assertFalse(presses.isValid)
        presses.disarm()
        target = null
        assertFalse(presses.arm())
    }

    @Test fun reopeningIsSilentAndExplicitGateSelectionExpiresWithoutAPress() {
        assertTrue(presses.arm(MicrophoneMode.CONTINUOUS))
        assertEquals(MicrophoneMode.PUSH_TO_TALK, presses.mode)
        assertNull(presses.capture)
        assertTrue(presses.select(MicrophoneMode.VOLUME_GATE) { error("no previous capture to revoke") })
        val capture = checkNotNull(presses.capture)
        assertEquals(MicrophoneMode.VOLUME_GATE, capture.mode)
        assertNull(presses.begin())
        presses.release(capture.token) { error("a hold release must not stop automatic capture") }
        now = 299_999L
        assertEquals(capture, presses.capture)
        now++
        assertNull(presses.capture)
        assertFalse(presses.select(MicrophoneMode.CONTINUOUS) { })
        assertTrue(presses.disarm())
    }

    @Test fun changingCaptureModesRevokesThePreviousTokenAndHoldStillRequiresAPress() {
        assertTrue(presses.arm())
        val hold = checkNotNull(presses.begin())
        var stops = 0
        assertTrue(presses.select(MicrophoneMode.CONTINUOUS) { stops++ })
        assertNotEquals(hold, presses.capture?.token)
        presses.release(hold) { error("stale hold release stopped continuous capture") }
        assertTrue(presses.select(MicrophoneMode.PUSH_TO_TALK) { stops++ })
        assertEquals(2, stops)
        assertNull(presses.capture)
    }
}
