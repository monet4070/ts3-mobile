package io.github.ts3mobile.app.service

internal data class ChannelRestoreTarget(
    val channelId: Int,
    val password: String,
)

internal object ChannelRestorePolicy {
    fun afterSnapshot(
        remembered: ChannelRestoreTarget?,
        observedChannelId: Int?,
        restorePending: Boolean,
    ): ChannelRestoreTarget? {
        if (restorePending || observedChannelId == null) return remembered
        if (remembered?.channelId == observedChannelId) return remembered
        return ChannelRestoreTarget(observedChannelId, password = "")
    }

    fun targetToRestore(
        remembered: ChannelRestoreTarget?,
        currentChannelId: Int?,
    ): ChannelRestoreTarget? =
        remembered?.takeUnless { target ->
            target.channelId == currentChannelId
        }
}
