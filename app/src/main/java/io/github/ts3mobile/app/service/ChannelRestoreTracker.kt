package io.github.ts3mobile.app.service

/**
 * Tracks the channel a reconnect should restore: the last channel target
 * remembered from snapshots or manual joins, and whether a restore join is
 * currently in flight. Pure state with no Android or coroutine dependencies;
 * the join itself stays in the coordinator.
 */
internal class ChannelRestoreTracker {
    private var lastChannel: ChannelRestoreTarget? = null
    private var restorePending = false

    fun reset() {
        lastChannel = null
        restorePending = false
    }

    /** Remembers the observed channel after a snapshot, unless a restore is pending. */
    fun rememberSnapshot(observedChannelId: Int?) {
        lastChannel =
            ChannelRestorePolicy.afterSnapshot(
                remembered = lastChannel,
                observedChannelId = observedChannelId,
                restorePending = restorePending,
            )
    }

    /** Seeds the target from the channel the session starts in. */
    fun seedFromCurrentChannel(currentChannelId: Int?) {
        if (lastChannel == null) {
            lastChannel = currentChannelId?.let { ChannelRestoreTarget(it, "") }
        }
    }

    fun rememberManualJoin(
        channelId: Int,
        password: String,
    ) {
        lastChannel = ChannelRestoreTarget(channelId, password)
    }

    fun targetToRestore(currentChannelId: Int?): ChannelRestoreTarget? =
        ChannelRestorePolicy.targetToRestore(
            remembered = lastChannel,
            currentChannelId = currentChannelId,
        )

    fun beginRestore() {
        restorePending = true
    }

    fun endRestore() {
        restorePending = false
    }
}
