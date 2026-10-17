package io.github.ts3mobile.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.resolve
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus

/** Foreground types describe listening and actual capture, without data-sync time limits. */
internal class SessionNotifications(
    private val service: Service,
    private val onTypesApplied: (Int) -> Unit,
) {
    private val manager = service.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun startConnecting(host: String) {
        promote(
            build(TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTING), serverLabel = host)),
            includeMicrophone = false,
        )
    }

    fun update(state: TeamSpeakServiceState) {
        if (state.status.phase !in foregroundPhases || state.serverLabel == null) return
        manager.notify(NOTIFICATION_ID, build(state))
    }

    fun updateType(
        state: TeamSpeakServiceState,
        includeMicrophone: Boolean,
    ) {
        if (state.status.phase !in foregroundPhases || state.serverLabel == null) return
        promote(build(state.copy(isTransmitting = includeMicrophone)), includeMicrophone)
    }

    fun stop() = service.stopForeground(Service.STOP_FOREGROUND_REMOVE)

    @SuppressLint("InlinedApi")
    private fun promote(
        notification: Notification,
        includeMicrophone: Boolean,
    ) {
        val types =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, types)
        onTypesApplied(types)
    }

    private fun build(state: TeamSpeakServiceState): Notification {
        val host = state.serverLabel.orEmpty()
        val onlineCount = state.snapshot.participants.size
        val text =
            when {
                state.microphoneError == UserMessage.MicrophoneResumeInApp && state.status.phase == ConnectionPhase.CONNECTED ->
                    service.getString(R.string.notification_resume_microphone)
                state.isTransmitting -> service.getString(R.string.notification_microphone_active, host, onlineCount)
                else ->
                    when (state.status.phase) {
                        ConnectionPhase.CONNECTING -> service.getString(R.string.notification_connecting, host)
                        ConnectionPhase.RECONNECTING ->
                            state.statusMessage?.resolve(service)
                                ?: service.getString(R.string.notification_reconnecting, host)
                        ConnectionPhase.DISCONNECTING -> service.getString(R.string.notification_disconnecting, host)
                        ConnectionPhase.CONNECTED ->
                            service.resources.getQuantityString(R.plurals.notification_connected, onlineCount, host, onlineCount)
                        ConnectionPhase.DISCONNECTED,
                        ConnectionPhase.ERROR,
                        -> host
                    }
            }
        val builder =
            NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(
                    PendingIntent.getActivity(
                        service,
                        0,
                        Intent(service, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .addAction(
                    0,
                    service.getString(R.string.notification_disconnect),
                    serviceAction(1, TeamSpeakService.ACTION_DISCONNECT),
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (state.microphoneMode == MicrophoneMode.CONTINUOUS) {
            builder.addAction(
                0,
                service.getString(R.string.notification_mute_microphone),
                serviceAction(2, TeamSpeakService.ACTION_MUTE_MICROPHONE),
            )
        }
        return builder.build()
    }

    private fun serviceAction(
        requestCode: Int,
        action: String,
    ): PendingIntent =
        PendingIntent.getService(
            service,
            requestCode,
            Intent(service, TeamSpeakService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL_ID = "ts3_connection"
        private const val NOTIFICATION_ID = 4103
        private val foregroundPhases =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.RECONNECTING,
                ConnectionPhase.CONNECTED,
                ConnectionPhase.DISCONNECTING,
            )
    }
}
