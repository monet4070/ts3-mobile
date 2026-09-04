package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRestoreTrackerTest {
    @Test
    fun remembersObservedChannelAndKeepsPendingTarget() {
        val tracker = ChannelRestoreTracker()
        tracker.rememberSnapshot(10)
        assertEquals(ChannelRestoreTarget(10, ""), tracker.targetToRestore(currentChannelId = null))

        // A snapshot arriving while a restore is pending must not overwrite
        // the remembered target (the password would be lost).
        tracker.rememberManualJoin(20, "secret")
        tracker.beginRestore()
        tracker.rememberSnapshot(30)
        assertEquals(ChannelRestoreTarget(20, "secret"), tracker.targetToRestore(currentChannelId = null))
    }

    @Test
    fun seedsOnlyWhenEmptyAndClearsAfterManualJoin() {
        val tracker = ChannelRestoreTracker()
        tracker.seedFromCurrentChannel(5)
        tracker.seedFromCurrentChannel(9)
        assertEquals(ChannelRestoreTarget(5, ""), tracker.targetToRestore(currentChannelId = null))

        tracker.rememberManualJoin(7, "pw")
        assertEquals(ChannelRestoreTarget(7, "pw"), tracker.targetToRestore(currentChannelId = null))
    }

    @Test
    fun noTargetWhenAlreadyInChannelOrReset() {
        val tracker = ChannelRestoreTracker()
        tracker.rememberManualJoin(3, "")
        assertNull(tracker.targetToRestore(currentChannelId = 3))
        assertNotNull(tracker.targetToRestore(currentChannelId = 4))

        tracker.reset()
        assertNull(tracker.targetToRestore(currentChannelId = 4))
    }
}
