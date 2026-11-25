package io.github.ts3mobile.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.floatingPttRecallIntent
import io.github.ts3mobile.app.ui.resolve
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus

/** Foreground types cover playback, capture and explicit floating PTT preparation. */
internal class SessionNotifications(
    private val service: Service,
    private val onTypesApplied: (Int) -> Unit,
) {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val channelId = SessionNotificationChannel.register(manager, service.getString(R.string.notification_channel_name))
    private val playbackActions = PlaybackNotificationActions()
    private val recallIntent by lazy {
        PendingIntent.getActivity(service, 4, service.floatingPttRecallIntent(), PendingIntent.FLAG_IMMUTABLE)
    }
    private var pttPrepared = false

    @Synchronized
    fun startConnecting(host: String) {
        promote(
            build(TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTING), serverLabel = host)),
            includeMicrophone = false,
        )
    }

    /** Android still requires promotion when a rejected foreground start remains bound. */
    fun rejectStart(message: Int) {
        val notification =
            NotificationCompat.Builder(service, channelId)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(service.getString(message))
                .setOnlyAlertOnce(true)
                .build()
        promote(notification, includeMicrophone = false)
        stop()
    }

    @Synchronized
    fun update(state: TeamSpeakServiceState) {
        if (state.status.phase !in foregroundPhases || state.serverLabel == null) return
        manager.notify(NOTIFICATION_ID, build(state))
    }

    @Synchronized
    fun updateType(
        state: TeamSpeakServiceState,
        includeMicrophone: Boolean,
    ) {
        if (pttPrepared || state.status.phase !in foregroundPhases || state.serverLabel == null) return
        promote(build(state.copy(isCapturing = includeMicrophone)), includeMicrophone)
    }

    @Synchronized
    fun setPttPrepared(
        state: TeamSpeakServiceState,
        ready: Boolean,
        inputReleased: () -> Boolean,
    ) {
        if (ready) {
            check(state.floatingPttTarget() != null && state.serverLabel != null)
            val channel = manager.getNotificationChannel(channelId)
            check(manager.areNotificationsEnabled() && channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE)
            promote(build(state, prepared = true), includeMicrophone = true)
        } else if (state.status.phase in foregroundPhases && state.serverLabel != null) {
            pttPrepared = false
            promote(build(state), includeMicrophone = !inputReleased())
        }
        pttPrepared = ready
    }

    @Synchronized
    fun stop() {
        pttPrepared = false
        playbackActions.clear()
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    fun consumePlaybackAction(
        id: String?,
        state: TeamSpeakServiceState,
        liveSafetyMuted: Boolean,
    ): Boolean? = playbackActions.consume(id, state, liveSafetyMuted)

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

    private fun build(
        state: TeamSpeakServiceState,
        prepared: Boolean = pttPrepared,
    ): Notification {
        val host = state.serverLabel.orEmpty()
        val onlineCount = state.snapshot.participants.size
        val text =
            when {
                prepared && state.isCapturing -> service.getString(R.string.notification_floating_ptt_capture)
                prepared -> service.getString(R.string.notification_floating_ptt_ready)
                state.hasPlaybackFailure ->
                    service.getString(R.string.playback_failed)
                state.microphoneError == UserMessage.MicrophoneResumeInApp && state.status.phase == ConnectionPhase.CONNECTED ->
                    service.getString(R.string.notification_resume_microphone)
                state.isCapturing && !state.isTransmitting && state.microphoneMode == MicrophoneMode.VOLUME_GATE ->
                    service.getString(R.string.notification_microphone_listening)
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
        val disconnect =
            NotificationCompat.Action(
                0,
                service.getString(R.string.notification_disconnect),
                serviceAction(1, TeamSpeakService.ACTION_DISCONNECT),
            )
        val listening = listeningControl(state)
        val recall =
            if (!prepared && !state.isCapturing && state.floatingPttTarget() != null) {
                NotificationCompat.Action(0, service.getString(R.string.notification_show_floating_ptt), recallIntent)
            } else {
                null
            }
        val builder =
            NotificationCompat.Builder(service, channelId)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(service.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(openApp())
                .addAction(disconnect)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (recall == null && state.microphoneMode.requiresContinuousCapture) {
            builder.addAction(
                0,
                service.getString(R.string.notification_mute_microphone),
                serviceAction(2, TeamSpeakService.ACTION_MUTE_MICROPHONE),
            )
        }
        listening?.let { builder.addAction(it) }
        recall?.let { builder.addAction(it) }
        if (state.status.phase == ConnectionPhase.CONNECTED) {
            builder.showConnectedControls(service, text, disconnect, listening, recall)
        }
        return builder.build()
    }

    private fun listeningControl(state: TeamSpeakServiceState): NotificationCompat.Action? =
        when (val action = playbackActions.render(state)) {
            PlaybackNotificationAction.CheckRoute ->
                NotificationCompat.Action(0, service.getString(R.string.notification_check_route), openApp())
            is PlaybackNotificationAction.SetMuted -> {
                val label = if (action.muted) R.string.notification_mute_playback else R.string.notification_resume_playback
                val intent =
                    Intent(service, TeamSpeakService::class.java)
                        .setAction(TeamSpeakService.ACTION_SET_PLAYBACK_MUTED)
                        .setData(Uri.fromParts("ts3-notification", action.id, null))
                NotificationCompat.Action(
                    0,
                    service.getString(label),
                    PendingIntent.getService(service, 3, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            }
            null -> null
        }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
        const val NOTIFICATION_ID = 4103
        private val foregroundPhases =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.RECONNECTING,
                ConnectionPhase.CONNECTED,
                ConnectionPhase.DISCONNECTING,
            )
    }
}
