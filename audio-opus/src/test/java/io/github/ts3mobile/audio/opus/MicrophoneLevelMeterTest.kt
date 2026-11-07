package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneLevelMeterTest {
    @Test fun subscribingNeverActivatesCaptureAndLastCloseClearsSnapshot() {
        val meter = MicrophoneLevelMeter { 0 }
        val first = meter.subscribe()
        val second = meter.subscribe()
        assertTrue(meter.isObserved())
        meter.offer(1, 0, 0, -20.0, false, true, true, -40)
        assertNull(first.latest)
        meter.activate(1, 0, 0)
        meter.offer(1, 0, 0, -20.0, false, true, true, -40)
        assertNotNull(first.latest)
        first.close()
        assertNull(first.latest)
        assertNotNull(second.latest)
        second.close()
        second.close()
        assertFalse(meter.isObserved())
        val reopened = meter.subscribe()
        assertNull(reopened.latest)
        reopened.close()
    }

    @Test fun publishesAtMostTenTimesPerSecondWithMonotonicClock() {
        var now = 0L
        val meter = MicrophoneLevelMeter { now }
        val subscription = meter.subscribe()
        meter.activate(1, 0, 0)
        meter.offer(1, 0, 0, -40.0, true, false, false, -40)
        repeat(4) {
            now += 20_000_000
            meter.offer(1, 0, 0, -20.0, true, true, true, -40)
            assertEquals(-40.0, checkNotNull(subscription.latest).rmsDb, 0.0)
        }
        now = 100_000_000
        meter.offer(1, 0, 0, -20.0, true, true, true, -40)
        assertEquals(-20.0, checkNotNull(subscription.latest).rmsDb, 0.0)
        assertEquals(now, subscription.latest?.measuredAtNanos)
        subscription.close()
    }

    @Test fun settingsRouteAndCaptureGenerationsRejectLateWorkerPublications() {
        var now = 0L
        val meter = MicrophoneLevelMeter { now }
        val subscription = meter.subscribe()
        meter.activate(1, 0, 0)
        meter.offer(1, 0, 0, -20.0, true, false, false, -40)
        meter.activate(1, 1, 0)
        assertNull(subscription.latest)
        meter.offer(1, 0, 0, -10.0, true, true, true, -40)
        assertNull(subscription.latest)
        meter.activate(1, 2, 1)
        meter.offer(1, 1, 0, -10.0, true, true, true, -40)
        assertNull(subscription.latest)
        now = 100_000_000
        meter.offer(1, 2, 1, -30.0, true, false, false, -35)
        assertEquals(-35, subscription.latest?.thresholdDb)
        meter.stop(1)
        assertNull(subscription.latest)
        meter.offer(1, 2, 1, -10.0, true, true, true, -40)
        assertNull(subscription.latest)
        meter.activate(2, 0, 0)
        meter.stop(1)
        meter.offer(2, 0, 0, -96.0, false, true, true, -40)
        assertEquals(2L, subscription.latest?.captureGeneration)
        subscription.close()
    }

    @Test fun rapidSettingsChangesClearStaleSamplesWithoutBypassingPublicationLimit() {
        var now = 0L
        val meter = MicrophoneLevelMeter { now }
        val subscription = meter.subscribe()
        meter.activate(1, 0, 0)
        meter.offer(1, 0, 0, -20.0, false, true, true, -40)
        repeat(4) { index ->
            now += 20_000_000
            val revision = index + 1L
            meter.activate(1, revision, 0)
            meter.offer(1, revision, 0, -10.0, false, true, true, -40)
            assertNull(subscription.latest)
        }
        now = 100_000_000
        meter.activate(1, 5, 0)
        meter.offer(1, 5, 0, -10.0, false, true, true, -40)
        assertEquals(-10.0, checkNotNull(subscription.latest).rmsDb, 0.0)
        subscription.close()
    }

    @Test fun lateConfigurationCannotReactivateAfterStopWinsWithoutTakingCaptureLifecycleLock() {
        val meter = MicrophoneLevelMeter { 0 }
        val subscription = meter.subscribe()
        var running = true
        meter.activate(1, 0, 0) { running }
        running = false
        meter.stop(1)
        meter.activate(1, 1, 0) { running }
        meter.offer(1, 1, 0, -20.0, false, true, true, -40)
        assertNull(subscription.latest)
        subscription.close()
    }
}
