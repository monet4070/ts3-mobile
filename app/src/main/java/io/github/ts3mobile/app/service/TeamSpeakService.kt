package io.github.ts3mobile.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.identity.IdentityVault
import io.github.ts3mobile.app.ui.resolve
import io.github.ts3mobile.audio.opus.AudioDeviceRouter
import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

internal class TeamSpeakService : Service(), ConnectionCoordinatorHost {
    override val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val sessionMutex = Mutex()
    override val reconnectPolicy = ReconnectPolicy()
    override val diagnosticsRecorder = DiagnosticsRecorder()
    override val sessionGeneration = SessionGeneration()
    override val mutableState = MutableStateFlow(TeamSpeakServiceState())
    override val networkAvailable = MutableStateFlow(false)
    private val state = mutableState.asStateFlow()
    private val binder = SessionBinder()
    private lateinit var connectionCoordinator: ConnectionCoordinator

    private lateinit var identityVault: IdentityVault
    private lateinit var audioPlayer: OpusAudioPlayer
    private lateinit var microphoneController: MicrophoneController
    private lateinit var audioRouter: AudioDeviceRouter
    private lateinit var connectivityManager: ConnectivityManager

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkAvailability(true)
            }

            override fun onLost(network: Network) {
                updateNetworkAvailability(hasUsableNetwork())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                updateNetworkAvailability(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                )
            }
        }

    override fun onCreate() {
        super.onCreate()
        identityVault = IdentityVault(applicationContext)
        audioPlayer = OpusAudioPlayer(applicationContext)
        microphoneController =
            MicrophoneController(
                context = applicationContext,
                scope = serviceScope,
                state = mutableState,
                diagnosticsRecorder = diagnosticsRecorder,
                refreshDiagnostics = ::refreshDiagnostics,
                updateForegroundType = ::updateForegroundType,
                updateNotification = ::updateNotification,
                conciseMessage = { conciseMessage(it) },
            )
        audioRouter = AudioDeviceRouter(applicationContext, ::onAudioRoutingChanged)
        connectionCoordinator = ConnectionCoordinator(this)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkAvailable.value = hasUsableNetwork()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.toServerConfig() ?: return START_NOT_STICKY
                startForegroundWithTypes(
                    buildNotification(
                        host = config.host,
                        status = ConnectionStatus(ConnectionPhase.CONNECTING),
                    ),
                    includeMicrophone = false,
                )
                connectionCoordinator.beginConnection(config)
            }

            ACTION_DISCONNECT -> connectionCoordinator.requestDisconnect()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        connectionCoordinator.close()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        microphoneController.close()
        audioPlayer.close()
        audioRouter.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun conciseMessage(error: Throwable): String {
        var cursor: Throwable? = error
        while (cursor != null) {
            cursor.message?.takeIf(String::isNotBlank)?.let { return it.take(180) }
            cursor = cursor.cause
        }
        return error::class.java.simpleName
    }

    override fun refreshDiagnostics() {
        val snapshot = diagnosticsRecorder.snapshot()
        mutableState.update { current -> current.copy(diagnostics = snapshot) }
    }

    override fun updateNotification() {
        val current = mutableState.value
        if (current.status.phase !in foregroundPhases) return
        val manager = getSystemService(NotificationManager::class.java)
        val host = current.serverLabel ?: return
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                host = host,
                status = current.status,
                statusMessage = current.statusMessage,
                onlineCount = current.snapshot.participants.size,
                microphoneActive = current.isTransmitting,
            ),
        )
    }

    override fun resetParticipantGains() {
        audioPlayer.replaceParticipantGains(emptyMap())
    }

    override fun resetMicrophoneForConnection() {
        microphoneController.resetForConnection()
    }

    override suspend fun stopMicrophoneCapture() {
        microphoneController.stopSerialized()
    }

    override suspend fun createIdentity(): String = identityVault.getOrCreate()

    override fun startAudioRouting() {
        audioRouter.start()
    }

    override fun stopAudioRouting() {
        audioRouter.stop()
    }

    override fun applySelectedPlaybackMuted() {
        audioPlayer.setMuted(mutableState.value.playbackMuted)
    }

    override fun startPlayback() {
        audioPlayer.start()
    }

    override fun stopPlayback() {
        audioPlayer.stop()
    }

    override fun clearPlaybackMuted() {
        audioPlayer.setMuted(false)
    }

    override fun attachMicrophoneTo(session: Ts3SessionClient) {
        microphoneController.attachTo(session)
    }

    override fun stopMicrophoneImmediately() {
        microphoneController.stopImmediately()
    }

    override fun reconcileMicrophone() {
        microphoneController.reconcile()
    }

    override fun applyParticipantAudioSettings(snapshot: SessionSnapshot) {
        val settings = mutableState.value.participantAudioSettings
        audioPlayer.replaceParticipantGains(
            snapshot.participants.associate { participant ->
                participant.id to (settings[participant.audioControlKey()]?.gain ?: 1f)
            },
        )
    }

    override fun submitVoiceFrame(frame: VoiceFrame) {
        audioPlayer.submit(frame)
    }

    override fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun requestStopSelf() {
        stopSelf()
    }

    private fun hasUsableNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun updateNetworkAvailability(available: Boolean) {
        networkAvailable.value = available
        connectionCoordinator.onNetworkChanged(available)
    }

    private fun setParticipantMuted(
        key: String,
        muted: Boolean,
    ) {
        updateParticipantAudioSettings(key) { copy(muted = muted) }
    }

    private fun setParticipantVolume(
        key: String,
        volumePercent: Int,
    ) {
        updateParticipantAudioSettings(key) {
            copy(volumePercent = volumePercent.coerceIn(0, MAX_PARTICIPANT_VOLUME_PERCENT))
        }
    }

    private inline fun updateParticipantAudioSettings(
        key: String,
        transform: ParticipantAudioSettings.() -> ParticipantAudioSettings,
    ) {
        if (key.isBlank()) return
        mutableState.update { current ->
            val previous = current.participantAudioSettings[key] ?: ParticipantAudioSettings()
            val updated = previous.transform()
            if (updated == previous) return@update current
            val settings = current.participantAudioSettings.toMutableMap()
            if (updated == ParticipantAudioSettings()) {
                settings.remove(key)
            } else {
                settings[key] = updated
            }
            current.copy(participantAudioSettings = settings)
        }
        applyParticipantAudioSettings(mutableState.value.snapshot)
    }

    private fun onAudioRoutingChanged(routing: AudioRoutingState) {
        audioPlayer.setPreferredDevice(audioRouter.preferredOutputDevice())
        microphoneController.setPreferredDevice(audioRouter.preferredInputDevice())
        mutableState.update { it.copy(audioRouting = routing) }
    }

    private fun updateForegroundType(includeMicrophone: Boolean) {
        val current = mutableState.value
        if (current.status.phase !in foregroundPhases) return
        val host = current.serverLabel ?: return
        startForegroundWithTypes(
            notification =
                buildNotification(
                    host = host,
                    status = current.status,
                    statusMessage = current.statusMessage,
                    onlineCount = current.snapshot.participants.size,
                    microphoneActive = includeMicrophone,
                ),
            includeMicrophone = includeMicrophone,
        )
    }

    @SuppressLint("InlinedApi")
    private fun startForegroundWithTypes(
        notification: android.app.Notification,
        includeMicrophone: Boolean,
    ) {
        val baseTypes =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val types =
            baseTypes or
                if (includeMicrophone) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun buildNotification(
        host: String,
        status: ConnectionStatus,
        statusMessage: UserMessage? = null,
        onlineCount: Int = 0,
        microphoneActive: Boolean = false,
    ) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_app)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(
            if (microphoneActive) {
                getString(R.string.notification_microphone_active, host, onlineCount)
            } else {
                when (status.phase) {
                    ConnectionPhase.CONNECTING -> getString(R.string.notification_connecting, host)
                    ConnectionPhase.RECONNECTING ->
                        statusMessage?.let { it.resolve(this) }
                            ?: getString(R.string.notification_reconnecting, host)
                    ConnectionPhase.DISCONNECTING -> getString(R.string.notification_disconnecting, host)
                    ConnectionPhase.CONNECTED ->
                        resources.getQuantityString(
                            R.plurals.notification_connected,
                            onlineCount,
                            host,
                            onlineCount,
                        )
                    ConnectionPhase.DISCONNECTED,
                    ConnectionPhase.ERROR,
                    -> host
                }
            },
        )
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            getString(R.string.notification_disconnect),
            PendingIntent.getService(
                this,
                1,
                Intent(this, TeamSpeakService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun Intent.toServerConfig(): ServerConfig? {
        val host = getStringExtra(EXTRA_HOST) ?: return null
        val nickname = getStringExtra(EXTRA_NICKNAME) ?: return null
        return ServerConfig(
            host = host,
            port = getIntExtra(EXTRA_PORT, 9987),
            nickname = nickname,
            password = getStringExtra(EXTRA_PASSWORD).orEmpty(),
        )
    }

    inner class SessionBinder : Binder() {
        val state: StateFlow<TeamSpeakServiceState>
            get() = this@TeamSpeakService.state

        fun setPlaybackMuted(muted: Boolean) {
            audioPlayer.setMuted(muted)
            mutableState.update { it.copy(playbackMuted = muted) }
        }

        fun setParticipantMuted(
            key: String,
            muted: Boolean,
        ) {
            this@TeamSpeakService.setParticipantMuted(key, muted)
        }

        fun setParticipantVolume(
            key: String,
            volumePercent: Int,
        ) {
            this@TeamSpeakService.setParticipantVolume(key, volumePercent)
        }

        fun selectAudioRoute(routeId: Int) {
            audioRouter.selectRoute(routeId)
        }

        fun setMicrophoneMode(mode: MicrophoneMode) {
            microphoneController.setMode(mode)
        }

        fun setPushToTalkPressed(pressed: Boolean) {
            microphoneController.setPushToTalkPressed(pressed)
        }

        fun releasePushToTalk() {
            microphoneController.setPushToTalkPressed(false)
        }

        fun joinChannel(
            channelId: Int,
            password: String = "",
        ) {
            connectionCoordinator.joinChannel(channelId, password)
        }

        fun diagnosticSnapshot(): DiagnosticsSnapshot = diagnosticsRecorder.snapshot()

        fun redactedDiagnosticsJson(): String = diagnosticSnapshot().toRedactedJson()

        fun reportMicrophonePermissionDenied() {
            microphoneController.reportPermissionDenied()
        }
    }

    companion object {
        private const val ACTION_CONNECT = "io.github.ts3mobile.action.CONNECT"
        private const val ACTION_DISCONNECT = "io.github.ts3mobile.action.DISCONNECT"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NICKNAME = "nickname"
        private const val EXTRA_PASSWORD = "password"
        private const val NOTIFICATION_CHANNEL_ID = "ts3_connection"
        private const val NOTIFICATION_ID = 4103
        private const val MAX_PARTICIPANT_VOLUME_PERCENT = 200
        private val foregroundPhases =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.RECONNECTING,
                ConnectionPhase.CONNECTED,
                ConnectionPhase.DISCONNECTING,
            )

        fun connect(
            context: Context,
            config: ServerConfig,
        ) {
            val intent =
                Intent(context, TeamSpeakService::class.java)
                    .setAction(ACTION_CONNECT)
                    .putExtra(EXTRA_HOST, config.host)
                    .putExtra(EXTRA_PORT, config.port)
                    .putExtra(EXTRA_NICKNAME, config.nickname)
                    .putExtra(EXTRA_PASSWORD, config.password)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, TeamSpeakService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }
}
