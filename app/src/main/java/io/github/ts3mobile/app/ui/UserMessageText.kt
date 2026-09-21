package io.github.ts3mobile.app.ui

import android.content.Context
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.UserMessage
import io.github.ts3mobile.audio.opus.AudioRouteKind
import io.github.ts3mobile.audio.opus.AudioRouteOption
import io.github.ts3mobile.audio.opus.AudioRoutingError
import io.github.ts3mobile.audio.opus.AudioRoutingErrorKind

/**
 * Resolves service-layer messages against the active locale. This is the only
 * place that turns a [UserMessage] into text, so the notification builder and
 * the Compose tree cannot drift apart.
 */
fun UserMessage.resolve(context: Context): String =
    when (this) {
        UserMessage.NotConnected -> context.getString(R.string.status_not_connected)
        UserMessage.SessionExpired -> context.getString(R.string.status_session_expired)
        UserMessage.WaitingForNetwork -> context.getString(R.string.status_waiting_for_network)
        is UserMessage.ConnectionInterrupted ->
            context.withOptionalCause(
                cause,
                R.string.status_connection_interrupted,
                R.string.status_connection_interrupted_cause,
            )

        is UserMessage.ReconnectFailed ->
            context.withOptionalCause(
                cause,
                R.string.status_reconnect_failed,
                R.string.status_reconnect_failed_cause,
            )

        is UserMessage.ReconnectScheduled ->
            context.getString(R.string.status_reconnect_scheduled, seconds, attempt)

        is UserMessage.Reconnecting -> context.getString(R.string.status_reconnecting_attempt, attempt)
        is UserMessage.ChannelJoinFailed -> context.getString(R.string.channel_join_failed, cause)
        is UserMessage.ChannelRestoreFailed -> context.getString(R.string.channel_restore_failed, cause)
        UserMessage.MicrophonePermissionForContinuous ->
            context.getString(R.string.microphone_permission_continuous)

        UserMessage.MicrophonePermissionMissing -> context.getString(R.string.microphone_permission_missing)
        UserMessage.MicrophonePermissionForVoice -> context.getString(R.string.microphone_permission_voice)
        is UserMessage.MicrophoneFailed -> context.getString(R.string.microphone_failed, cause)
        is UserMessage.AudioRouting -> error.resolve(context)
    }

/** Names an audio route, appending the platform device name when one is known. */
fun AudioRouteOption.resolveLabel(context: Context): String {
    val kindLabel = context.getString(kind.labelId())
    val name = deviceName?.takeIf { it.isNotBlank() && !it.equals(kindLabel, ignoreCase = true) }
    return name?.let { context.getString(R.string.audio_route_with_device, kindLabel, it) } ?: kindLabel
}

private fun AudioRoutingError.resolve(context: Context): String =
    when (kind) {
        AudioRoutingErrorKind.DEVICE_UNAVAILABLE -> context.getString(R.string.audio_route_unavailable)
        AudioRoutingErrorKind.SWITCH_FAILED ->
            context.getString(R.string.audio_route_switch_failed, cause.orEmpty())

        AudioRoutingErrorKind.SWITCH_DENIED -> context.getString(R.string.audio_route_switch_denied)
        AudioRoutingErrorKind.DEVICE_DISCONNECTED -> context.getString(R.string.audio_route_disconnected)
    }

private fun AudioRouteKind.labelId(): Int =
    when (this) {
        AudioRouteKind.SYSTEM -> R.string.audio_route_system
        AudioRouteKind.EARPIECE -> R.string.audio_route_earpiece
        AudioRouteKind.SPEAKER -> R.string.audio_route_speaker
        AudioRouteKind.WIRED -> R.string.audio_route_wired
        AudioRouteKind.BLUETOOTH -> R.string.audio_route_bluetooth
        AudioRouteKind.USB -> R.string.audio_route_usb
        AudioRouteKind.OTHER -> R.string.audio_route_other
    }

private fun Context.withOptionalCause(
    cause: String?,
    plainId: Int,
    withCauseId: Int,
): String = cause?.let { getString(withCauseId, it) } ?: getString(plainId)
