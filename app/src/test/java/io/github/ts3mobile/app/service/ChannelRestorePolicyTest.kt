package io.github.ts3mobile.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelRestorePolicyTest {
    @Test
    fun keepsPasswordWhenTheExpectedChannelSnapshotArrives() {
        val remembered = ChannelRestoreTarget(channelId = 42, password = "secret")

        val updated =
            ChannelRestorePolicy.afterSnapshot(
                remembered = remembered,
                observedChannelId = 42,
                restorePending = false,
            )

        assertEquals(remembered, updated)
    }

    @Test
    fun reconnectDefaultChannelCannotOverwritePendingPasswordTarget() {
        val remembered = ChannelRestoreTarget(channelId = 42, password = "secret")

        val updated =
            ChannelRestorePolicy.afterSnapshot(
                remembered = remembered,
                observedChannelId = 1,
                restorePending = true,
            )

        assertEquals(remembered, updated)
    }

    @Test
    fun userChannelMoveReplacesTheOldTargetWithoutInventingAPassword() {
        val updated =
            ChannelRestorePolicy.afterSnapshot(
                remembered = ChannelRestoreTarget(channelId = 42, password = "secret"),
                observedChannelId = 7,
                restorePending = false,
            )

        assertEquals(ChannelRestoreTarget(channelId = 7, password = ""), updated)
    }

    @Test
    fun restoreIsSkippedWhenAlreadyInTheRememberedChannel() {
        val remembered = ChannelRestoreTarget(channelId = 42, password = "secret")

        assertNull(ChannelRestorePolicy.targetToRestore(remembered, currentChannelId = 42))
        assertEquals(remembered, ChannelRestorePolicy.targetToRestore(remembered, currentChannelId = 1))
    }
}
