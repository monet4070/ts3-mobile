package io.github.ts3mobile.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSelection
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.bookmarks.bookmarkContext
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.PokeRecipient
import io.github.ts3mobile.app.chat.SessionChatController
import io.github.ts3mobile.app.history.SuccessServerHistoryStore
import io.github.ts3mobile.app.history.successServerHistoryStore
import io.github.ts3mobile.app.identity.IdentityVault
import io.github.ts3mobile.app.settings.MicrophonePreferenceController
import io.github.ts3mobile.app.settings.MicrophonePreferenceField
import io.github.ts3mobile.app.settings.MicrophonePreferenceState
import io.github.ts3mobile.app.settings.microphonePreferenceStore
import io.github.ts3mobile.audio.opus.AudioDeviceRouter
import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
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
    private val chatController = SessionChatController(serviceScope)
    private lateinit var sessionControls: SessionControlsController
    private lateinit var connectionCoordinator: ConnectionCoordinator

    private lateinit var identityVault: IdentityVault
    private lateinit var audioPlayer: OpusAudioPlayer
    private val captureRoutes = CaptureRouteTracker()

    @Volatile private var destroyed = false
    private lateinit var microphoneController: MicrophoneController
    private lateinit var microphonePreferences: MicrophonePreferenceController
    private lateinit var audioRouter: AudioDeviceRouter
    private lateinit var historyStore: SuccessServerHistoryStore
    private lateinit var sessionDiagnostics: SessionDiagnostics
    private lateinit var backgroundRuntime: BackgroundRuntimeController
    private lateinit var notifications: SessionNotifications
    private lateinit var pokeAlerts: PokeAlertObserver
    private val chatNotificationRegistry = ChatNotificationRegistry(SystemClock::elapsedRealtime)
    private lateinit var privateMessageAlerts: PrivateMessageAlertObserver
    private lateinit var networkMonitor: DefaultNetworkMonitor

    /** Re-checks request ownership on the main thread before teardown side effects. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestStartId = 0

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
        sessionControls =
            SessionControlsController(serviceScope, mutableState) {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        val playbackStatus =
            PlaybackStatusController(
                mutableState,
                { sessionDiagnostics.record(DiagnosticEventKind.PLAYBACK_FAILED) },
                ::updateNotification,
            )
        audioPlayer =
            OpusAudioPlayer(applicationContext, { microphoneController.invalidateRouting() }, playbackStatus::update) {
                audioRouter.onBluetoothRouteLost()
            }
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
        microphonePreferences =
            MicrophonePreferenceController(serviceScope, microphonePreferenceStore(applicationContext)) { settings ->
                // Queue every application, including UI edits, in the controller's
                // publication order so an IO load cannot overtake a newer UI edit.
                mainHandler.post { if (!destroyed) microphoneController.setSettings(settings) }
            }
        audioRouter = AudioDeviceRouter(applicationContext, ::onAudioRoutingChanged)
        connectionCoordinator = ConnectionCoordinator(this, clockMs = SystemClock::elapsedRealtime, chat = chatController)
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
        val pokeNotifications = PokeNotifications(applicationContext)
        pokeAlerts = PokeAlertObserver(serviceScope, chatController.state, pokeNotifications::show, pokeNotifications::clear)
        val privateMessageNotifications = PrivateMessageNotifications(applicationContext)
        privateMessageAlerts =
            PrivateMessageAlertObserver(
                serviceScope, chatController.state, chatNotificationRegistry,
                privateMessageNotifications::show, privateMessageNotifications::clear,
            )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = startId
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

    /**
     * The task was removed from the recents list.
     *
     * Only the fixed category is recorded here; the journal append is in-memory and
     * the exit request is registered synchronously, so neither disk nor network
     * blocks the main thread. The bounded exit itself runs on IO.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        sessionDiagnostics.record(DiagnosticEventKind.TASK_REMOVED)
        connectionCoordinator.requestExit(ExitTrigger.TASK_REMOVED)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        // A queued exit teardown must not touch a destroyed service.
        mainHandler.removeCallbacksAndMessages(null)
        connectionCoordinator.close()
        backgroundRuntime.close()
        sessionDiagnostics.close()
        networkMonitor.close()
        sessionControls.close()
        pokeAlerts.close()
        privateMessageAlerts.close()
        microphonePreferences.close()
        microphoneController.closeAfterRelease {
            mainHandler.post {
                audioRouter.close()
                notifications.stop()
            }
        }
        audioPlayer.close()
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
        if (!destroyed) notifications.update(mutableState.value)
    }

    private fun updateForegroundType(includeMicrophone: Boolean) {
        if (!destroyed) notifications.updateType(mutableState.value, includeMicrophone)
    }

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
        sessionControls.attach(session)
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

    override fun recordDiagnostic(
        kind: DiagnosticEventKind,
        code: Long,
    ) {
        sessionDiagnostics.record(kind, code)
    }

    /**
     * The exit asked to remove the notification and stop the service.
     *
     * The exit token is re-checked here, on the main thread, in the same action
     * that performs both side effects: a connect the main thread already processed
     * (or a later exit) owns the service now, and an older exit whose teardown was
     * still queued must not stop it. The newest start id is used, so a
     * notification action that arrived in between keeps the service alive.
     */
    override fun finishExitOnMainThread(exitToken: Long) {
        mainHandler.post {
            if (!connectionCoordinator.isLatestExit(exitToken)) return@post
            removeForegroundNotification()
            stopSelfResult(latestStartId)
        }
    }

    override fun finishTerminalOnMainThread(epoch: Long) {
        mainHandler.post {
            if (!sessionGeneration.isActive(epoch)) return@post
            removeForegroundNotification()
            stopSelfResult(latestStartId)
        }
    }

    /**
     * The bounded clone-limit recovery gave up. The epoch is re-checked on the main
     * thread; the service keeps running so the explanation stays readable and the
     * user can retry manually, and only the foreground notification is removed.
     */
    override fun removeForegroundNotificationOnMainThread(epoch: Long) {
        mainHandler.post {
            if (!sessionGeneration.isActive(epoch)) return@post
            removeForegroundNotification()
        }
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
        if (routing.safetyMuted) {
            audioPlayer.setMuted(true)
            mutableState.update { it.copy(playbackMuted = true) }
        }
        audioPlayer.updateRouting(routing, audioRouter.preferredOutputDevice())
        val input = audioRouter.preferredInputDevice()
        captureRoutes.update(routing.selectedRouteId, input?.id)?.let { change ->
            if (change.inputChanged) microphoneController.setPreferredDevice(input) else microphoneController.invalidateRouting()
        }
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
            defaultChannelId = getIntExtra(EXTRA_DEFAULT_CHANNEL, 0).takeIf { it > 0 },
            channelPassword = getStringExtra(EXTRA_CHANNEL_PASSWORD).orEmpty(),
        )
    }

    inner class SessionBinder : Binder() {
        fun validateBookmarkSelection(
            entry: ServerBookmark,
            selection: BookmarkChannelSelection,
        ): Boolean = state.value.bookmarkContext()?.accepts(entry, selection) == true

        val microphonePreferenceState: StateFlow<MicrophonePreferenceState> get() = microphonePreferences.state
        val microphoneLevels get() = microphoneController.levels
        val chatState: StateFlow<ChatState> get() = chatController.state

        fun submitChannelText() = chatController.submitChannel()

        fun submitText() = chatController.submit()

        fun poke(
            recipient: PokeRecipient,
            reason: String,
        ) = chatController.poke(recipient, reason)

        fun dismissPoke(id: Long) = chatController.dismissPoke(id)

        fun openPrivateChat(clientId: Int) = chatController.openPrivate(clientId)

        fun selectChat(key: ChatConversationKey) = chatController.select(key)

        fun closeChat(key: ChatConversationKey) = chatController.closeConversation(key)

        fun updateChatDraft(body: String) = chatController.updateDraft(body)

        fun markChatVisibleThrough(sequence: Long) = chatController.markVisibleThrough(sequence)

        fun markChatVisibleThrough(
            key: ChatConversationKey,
            sequence: Long,
        ) = chatController.markVisibleThrough(key, sequence)

        val state: StateFlow<TeamSpeakServiceState>
            get() = this@TeamSpeakService.state

        fun setAppVisible(visible: Boolean) {
            pokeAlerts.setAppVisible(visible)
            privateMessageAlerts.setAppVisible(visible)
            if (visible) refreshMicrophonePermission()
            microphoneController.setAppVisible(visible)
            if (visible) backgroundRuntime.refresh()
        }

        fun consumeChatNotification(token: String) = chatNotificationRegistry.consume(token, chatController.state.value)

        fun refreshMicrophonePermission() {
            microphoneController.refreshPermission()
            sessionControls.refreshPermission()
        }

        fun setKeepCpuAwake(enabled: Boolean) = backgroundRuntime.setKeepCpuAwake(enabled)

        fun refreshBackgroundRuntime() = backgroundRuntime.refresh()

        fun setPlaybackMuted(muted: Boolean) {
            if (!muted) audioRouter.acknowledgeSafetyMute()
            val effectiveMute = muted || audioRouter.state.safetyMuted
            audioPlayer.setMuted(effectiveMute)
            mutableState.update { it.copy(playbackMuted = effectiveMute) }
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

        fun resolveUserChannel(
            clientId: Int,
            expectedUid: String,
        ): Int? =
            if (mutableState.value.status.phase == io.github.ts3mobile.protocol.ConnectionPhase.CONNECTED) {
                io.github.ts3mobile.app.discovery.resolveParticipantChannel(
                    mutableState.value.snapshot,
                    clientId,
                    expectedUid,
                )
            } else {
                null
            }

        fun setAway(
            away: Boolean,
            message: String,
        ) = sessionControls.setAway(away, message)

        fun retryPresence() = sessionControls.retryPresence()

        fun setMicrophoneSettings(settings: MicrophoneSettings) = microphonePreferences.replace(settings)

        fun editMicrophonePreference(
            field: MicrophonePreferenceField,
            settings: MicrophoneSettings,
        ) = microphonePreferences.edit(
            field,
            settings,
        )

        fun commitMicrophonePreferences() = microphonePreferences.commit()

        fun retryMicrophonePreferences() = microphonePreferences.retry()

        fun resetMicrophonePreferences() = microphonePreferences.reset()

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
            expectedConnectionToken: String? = null,
        ) {
            connectionCoordinator.joinChannel(channelId, password, expectedConnectionToken)
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
        private const val EXTRA_DEFAULT_CHANNEL = "default_channel"
        private const val EXTRA_CHANNEL_PASSWORD = "channel_password"

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
                    .putExtra(EXTRA_DEFAULT_CHANNEL, config.defaultChannelId ?: 0)
                    .putExtra(EXTRA_CHANNEL_PASSWORD, config.channelPassword)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, TeamSpeakService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }
}
