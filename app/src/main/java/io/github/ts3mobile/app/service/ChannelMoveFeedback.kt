package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ChannelMoveNotSubmittedException
import io.github.ts3mobile.protocol.ChannelMoveUnconfirmedException
import io.github.ts3mobile.protocol.SessionSnapshot

internal fun channelMoveMessage(
    error: Throwable,
    targetChannelId: Int,
    observedChannelId: Int?,
    restoring: Boolean,
    detail: () -> String,
): UserMessage? =
    when (error) {
        is NotConnectedException -> UserMessage.NotConnected
        is ChannelMoveNotSubmittedException -> UserMessage.ChannelMoveNotSubmitted
        is ChannelMoveUnconfirmedException ->
            if (observedChannelId == targetChannelId) null else UserMessage.ChannelMoveUnconfirmed(targetChannelId)
        else -> if (restoring) UserMessage.ChannelRestoreFailed(detail()) else UserMessage.ChannelJoinFailed(detail())
    }

internal fun TeamSpeakServiceState.withObservedChannel(snapshot: SessionSnapshot): TeamSpeakServiceState {
    val unconfirmed = channelError as? UserMessage.ChannelMoveUnconfirmed
    return copy(
        snapshot = snapshot,
        channelError = if (unconfirmed != null && unconfirmed.channelId == snapshot.currentChannelId) null else channelError,
    )
}
