package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionPowerControllerTest {
    @Test
    fun silentConnectedSessionRenewsUntilDisconnect() =
        runTest {
            val lock = FakeLock()
            val controller = SessionPowerController(backgroundScope, lock, { testScheduler.currentTime }, {})
            controller.update(true, ConnectionPhase.CONNECTED, true)
            assertTrue(lock.isHeld)
            advanceTimeBy(900_001)
            runCurrent()
            assertTrue(lock.acquisitions >= 4)
            controller.update(true, ConnectionPhase.DISCONNECTING, true)
            assertFalse(lock.isHeld)
            controller.close()
        }

    @Test
    fun recoveryExpiresAndNetworkFlapsCannotResetBudget() =
        runTest {
            val lock = FakeLock()
            val controller = SessionPowerController(backgroundScope, lock, { testScheduler.currentTime }, {})
            controller.update(true, ConnectionPhase.RECONNECTING, true)
            advanceTimeBy(60_000)
            controller.update(true, ConnectionPhase.RECONNECTING, false)
            assertFalse(lock.isHeld)
            advanceTimeBy(30_000)
            controller.update(true, ConnectionPhase.RECONNECTING, true)
            assertEquals(30_000L, lock.lastLease)
            advanceTimeBy(30_001)
            runCurrent()
            assertFalse(lock.isHeld)
            controller.update(true, ConnectionPhase.RECONNECTING, true)
            assertFalse(lock.isHeld)
            controller.close()
        }

    @Test
    fun systemTimeoutStillReportsAReleasedLock() =
        runTest {
            val lock = FakeLock()
            val reports = mutableListOf<Boolean>()
            val controller = SessionPowerController(backgroundScope, lock, { testScheduler.currentTime }, { reports += it })
            controller.update(true, ConnectionPhase.RECONNECTING, true)
            runCurrent()
            // Android may expire the timed lock before the renewal coroutine resumes.
            lock.isHeld = false
            advanceTimeBy(120_001)
            runCurrent()
            assertEquals(listOf(true, false), reports)
            controller.close()
        }

    @Test
    fun staleConnectedUpdateCannotReacquireAfterUserDisconnect() =
        runTest {
            val lock = FakeLock()
            var active = true
            val controller =
                SessionPowerController(backgroundScope, lock, { testScheduler.currentTime }, {}, isSessionActive = { active })
            controller.update(true, ConnectionPhase.CONNECTED, true)
            active = false
            controller.update(false, ConnectionPhase.CONNECTED, false)
            controller.update(true, ConnectionPhase.CONNECTED, true)
            assertFalse(lock.isHeld)
            controller.close()
        }

    @Test
    fun optInTerminalExitAndCloseReleaseResources() =
        runTest {
            val lock = FakeLock()
            val controller = SessionPowerController(backgroundScope, lock, { testScheduler.currentTime }, {})
            controller.update(false, ConnectionPhase.CONNECTED, true)
            assertFalse(lock.isHeld)
            controller.update(true, ConnectionPhase.CONNECTED, true)
            controller.update(true, ConnectionPhase.ERROR, true)
            assertFalse(lock.isHeld)
            controller.update(true, ConnectionPhase.CONNECTED, true)
            controller.close()
            assertFalse(lock.isHeld)
            controller.update(true, ConnectionPhase.CONNECTED, true)
            assertFalse(lock.isHeld)
        }

    private class FakeLock : SessionCpuLock {
        override var isHeld = false
        var acquisitions = 0
        var lastLease = 0L

        override fun acquire(timeoutMs: Long) {
            isHeld = true
            acquisitions++
            lastLease = timeoutMs
        }

        override fun release() {
            isHeld = false
        }
    }
}
