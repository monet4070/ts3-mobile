package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioRoutingError

/**
 * User-facing text produced by the service layer, kept as data instead of a
 * formatted string so the Android boundary resolves it against the active
 * locale. Variants carrying a [cause] embed technical detail that already
 * arrives in English from the protocol or platform and stays untranslated.
 */
sealed interface UserMessage {
    /** A channel action was requested while no session was established. */
    data object NotConnected : UserMessage

    /** The session was replaced or invalidated before the action completed. */
    data object SessionExpired : UserMessage

    /** Reconnect is deferred until the device has a network again. */
    data object WaitingForNetwork : UserMessage

    data class ConnectionInterrupted(val cause: String?) : UserMessage

    data class ReconnectFailed(val cause: String?) : UserMessage

    data class ReconnectScheduled(val seconds: Long, val attempt: Int) : UserMessage

    data class Reconnecting(val attempt: Int) : UserMessage

    data class ChannelJoinFailed(val cause: String) : UserMessage

    data class ChannelRestoreFailed(val cause: String) : UserMessage

    data object MicrophonePermissionForContinuous : UserMessage

    data object MicrophonePermissionMissing : UserMessage

    data object MicrophonePermissionForVoice : UserMessage

    data class MicrophoneFailed(val cause: String) : UserMessage

    data class AudioRouting(val error: AudioRoutingError) : UserMessage
}

/** Signals that no established session is available; mapped to [UserMessage.NotConnected]. */
internal class NotConnectedException : IllegalStateException("no established session")

/** Signals that the session ended while an action was in flight; mapped to [UserMessage.SessionExpired]. */
internal class SessionExpiredException : IllegalStateException("session no longer active")
