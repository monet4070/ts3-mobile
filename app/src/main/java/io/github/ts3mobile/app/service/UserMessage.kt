package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioRoutingError

/** Conservative wait for manual guidance and the one-shot handoff retry, not a server deadline. */
internal const val RECOMMENDED_RETRY_WAIT_SECONDS = 60L

/**
 * User-facing text produced by the service layer, kept as data instead of a
 * formatted string so the Android boundary resolves it against the active
 * locale. Variants carrying a [cause] embed technical detail that already
 * arrives in English from the protocol or platform and stays untranslated.
 */
sealed interface UserMessage {
    /** A channel action was requested while no session was established. */
    data object NotConnected : UserMessage

    data object IdentityMaintenance : UserMessage

    /** An earlier session or audio teardown still holds the session lock after bounded wait. */
    data object PreviousSessionBusy : UserMessage

    /** The session was replaced or invalidated before the action completed. */
    data object SessionExpired : UserMessage

    /** Reconnect is deferred until the device has a network again. */
    data object WaitingForNetwork : UserMessage

    data class ConnectionInterrupted(val cause: String?) : UserMessage

    data class ReconnectFailed(val cause: String?) : UserMessage

    data class ReconnectScheduled(val seconds: Long, val attempt: Int) : UserMessage

    data class Reconnecting(val attempt: Int) : UserMessage

    data class HandoffIdentityWait(val seconds: Long) : UserMessage

    /** Server refused this identity: no automatic attempts; wait and retry manually. */
    data object ConnectionLimitWait : UserMessage

    data class ChannelJoinFailed(val cause: String) : UserMessage

    data object ChannelTargetChanged : UserMessage

    data class ChannelRestoreFailed(val cause: String) : UserMessage

    data object ChannelMoveNotSubmitted : UserMessage

    data class ChannelMoveUnconfirmed(val channelId: Int) : UserMessage

    data object MicrophonePermissionForContinuous : UserMessage

    data object MicrophonePermissionMissing : UserMessage

    data object MicrophonePermissionForVoice : UserMessage

    data object MicrophoneResumeInApp : UserMessage

    data object MicrophoneTrialReleaseFailed : UserMessage

    data class MicrophoneFailed(val cause: String) : UserMessage

    data class AudioRouting(val error: AudioRoutingError) : UserMessage
}

/** Signals that no established session is available; mapped to [UserMessage.NotConnected]. */
internal class NotConnectedException : IllegalStateException("no established session")

/** Signals that the session ended while an action was in flight; mapped to [UserMessage.SessionExpired]. */
internal class SessionExpiredException : IllegalStateException("session no longer active")
