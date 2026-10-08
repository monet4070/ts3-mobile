package io.github.ts3mobile.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.history.SuccessServerHistoryStore
import io.github.ts3mobile.app.history.successServerHistoryStore
import io.github.ts3mobile.app.identity.IdentityVault
import io.github.ts3mobile.audio.opus.AudioDeviceRouter
import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.audio.opus.OpusAudioPlayer
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private lateinit var historyStore: SuccessServerHistoryStore
    private lateinit var sessionDiagnostics: SessionDiagnostics
    private lateinit var backgroundRuntime: BackgroundRuntimeController
    private lateinit var notifications: SessionNotifications
    private lateinit var networkMonitor: DefaultNetworkMonitor

    override fun onCreate() {
        super.onCreate()
        sessionDiagnostics = SessionDiagnostics(applicationContext, serviceScope)
        sessionDiagnostics.start(state)
        historyStore = successServerHistoryStore(applicationContext)
        notifications =
            SessionNotifications(this) { types ->
                sessionDiagnostics.record(DiagnosticEventKind.FOREGROUND_TYPES, types.toLong())
            }
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
                recordEvent = { sessionDiagnostics.record(it) },
            )
        audioRouter = AudioDeviceRouter(applicationContext, ::onAudioRoutingChanged)
        connectionCoordinator = ConnectionCoordinator(this, clockMs = SystemClock::elapsedRealtime)
        networkMonitor =
            DefaultNetworkMonitor(applicationContext) { network ->
                networkAvailable.value =
                    when {
                        network.isBlocked -> false
                        network.isPending -> networkAvailable.value
                        else -> network.isAvailable
                    }
                val kind =
                    when {
                        network.isBlocked -> DiagnosticEventKind.NETWORK_BLOCKED
                        network.isPending -> DiagnosticEventKind.NETWORK_PENDING
                        !network.isAvailable -> DiagnosticEventKind.NETWORK_UNAVAILABLE
                        else -> DiagnosticEventKind.NETWORK_CHANGED
                    }
                sessionDiagnostics.record(kind, network.generation)
                connectionCoordinator.onNetworkChanged(network)
            }
        networkMonitor.start()
        backgroundRuntime =
            BackgroundRuntimeController(
                applicationContext,
                serviceScope,
                mutableState,
                networkAvailable,
                sessionGeneration,
                { kind, code -> sessionDiagnostics.record(kind, code) },
            )
        backgroundRuntime.start()
        notifications.createChannel()
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
                sessionDiagnostics.record(DiagnosticEventKind.CONNECT_REQUESTED)
                notifications.startConnecting(config.host)
                connectionCoordinator.beginConnection(config)
            }

            ACTION_DISCONNECT -> {
                sessionDiagnostics.record(DiagnosticEventKind.USER_DISCONNECT)
                connectionCoordinator.requestDisconnect()
                backgroundRuntime.releaseForDisconnect()
            }
            ACTION_MUTE_MICROPHONE -> microphoneController.setMode(MicrophoneMode.OFF)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        connectionCoordinator.close()
        backgroundRuntime.close()
        sessionDiagnostics.close()
        networkMonitor.close()
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

    override fun updateNotification() = notifications.update(mutableState.value)

    private fun updateForegroundType(includeMicrophone: Boolean) = notifications.updateType(mutableState.value, includeMicrophone)

    override fun onConnectionSucceeded(config: ServerConfig) {
        sessionDiagnostics.record(DiagnosticEventKind.CONNECTED)
        serviceScope.launch {
            try {
                historyStore.recordSuccess(config.host, config.port)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                sessionDiagnostics.record(DiagnosticEventKind.HISTORY_WRITE_FAILED)
            }
        }
    }

    override fun onConnectionAttempt(reconnecting: Boolean) {
        sessionDiagnostics.record(
            if (reconnecting) DiagnosticEventKind.RECONNECT_ATTEMPT else DiagnosticEventKind.CONNECTION_ATTEMPT,
        )
    }

    override fun onManualConnectionStarted() = backgroundRuntime.startManualSession()

    override fun onConnectionFailure(status: ConnectionStatus) = sessionDiagnostics.recordFailure(status)

    override fun onTransportDisconnected() = sessionDiagnostics.record(DiagnosticEventKind.TRANSPORT_DISCONNECTED)

    override fun onWatchExecutionGap(gapSeconds: Long) = sessionDiagnostics.record(DiagnosticEventKind.WATCH_EXECUTION_GAP, gapSeconds)

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
        backgroundRuntime.releaseForDisconnect()
        notifications.stop()
    }

    override fun requestStopSelf() {
        stopSelf()
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

        fun setAppVisible(visible: Boolean) {
            microphoneController.setAppVisible(visible)
            if (visible) backgroundRuntime.refresh()
        }

        fun setKeepCpuAwake(enabled: Boolean) = backgroundRuntime.setKeepCpuAwake(enabled)

        fun refreshBackgroundRuntime() = backgroundRuntime.refresh()

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

        fun redactedDiagnosticsJson(): String = sessionDiagnostics.export(diagnosticSnapshot())

        fun reportMicrophonePermissionDenied() {
            microphoneController.reportPermissionDenied()
        }
    }

    companion object {
        private const val ACTION_CONNECT = "io.github.ts3mobile.action.CONNECT"
        internal const val ACTION_DISCONNECT = "io.github.ts3mobile.action.DISCONNECT"
        internal const val ACTION_MUTE_MICROPHONE = "io.github.ts3mobile.action.MUTE_MICROPHONE"
        private const val MAX_PARTICIPANT_VOLUME_PERCENT = 200
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NICKNAME = "nickname"
        private const val EXTRA_PASSWORD = "password"

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
