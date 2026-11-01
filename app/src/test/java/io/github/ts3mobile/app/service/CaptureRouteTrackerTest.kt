package io.github.ts3mobile.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRouteTrackerTest {
    @Test fun unchangedSelectionAndInputDoNotInvalidateForDeviceListOrErrorChanges() {
        val tracker = CaptureRouteTracker()
        assertTrue(tracker.update(-1, null) != null)
        repeat(10) { assertTrue(tracker.update(-1, null) == null) }
        assertFalse(tracker.update(2, null)!!.inputChanged)
        assertTrue(tracker.update(2, null) == null)
        assertTrue(tracker.update(2, 3)!!.inputChanged)
        assertTrue(tracker.update(2, 3) == null)
        assertTrue(tracker.update(-1, null)!!.inputChanged)
    }
}
