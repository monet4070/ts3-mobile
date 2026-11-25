package io.github.ts3mobile.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.invite.ServerInvite
import io.github.ts3mobile.app.probe.ConnectedPttProbeHost
import io.github.ts3mobile.app.probe.autoShowFloatingPtt
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.PrivateMessageNotificationIntent
import io.github.ts3mobile.app.service.PrivateMessageNotifications
import io.github.ts3mobile.app.service.TeamSpeakService
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.allowsOfflineActions
import io.github.ts3mobile.app.settings.MicrophonePreferenceState
import io.github.ts3mobile.app.ui.InviteDialog
import io.github.ts3mobile.app.ui.MainScreen
import io.github.ts3mobile.app.ui.MicrophoneTrialDialog
import io.github.ts3mobile.app.ui.PasteInviteAction
import io.github.ts3mobile.app.ui.shareInvite
import io.github.ts3mobile.app.ui.theme.ApplicationTheme
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private var serviceBinder by mutableStateOf<TeamSpeakService.SessionBinder?>(null)
    private val diagnosticReports = DiagnosticReportActions(this) { serviceBinder?.redactedDiagnosticsJson() }
    private var isBound = false
    private var pushToTalkPressed = false
    private var activityVisible = false

    private val notificationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            viewModel.notificationPermissionResolved = true
            launchPendingConnectionIfVisible()
        }

    private val microphonePermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            serviceBinder?.refreshMicrophonePermission()
            val requestedMode = viewModel.pendingMicrophoneMode
            viewModel.pendingMicrophoneMode = null
            if (granted && requestedMode != null) {
                serviceBinder?.setMicrophoneMode(requestedMode)
            } else if (granted && pushToTalkPressed) {
                serviceBinder?.setPushToTalkPressed(true)
            } else if (!granted) {
                pushToTalkPressed = false
                serviceBinder?.reportMicrophonePermissionDenied()
            }
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                serviceBinder = binder as? TeamSpeakService.SessionBinder
                serviceBinder?.setAppVisible(activityVisible)
                resolveChatNotification()
                launchPendingConnectionIfVisible()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBinder = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptEntryIntent(intent)
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                val fallbackState = remember { MutableStateFlow(TeamSpeakServiceState()) }
                val serviceState by (serviceBinder?.state ?: fallbackState)
                    .collectAsStateWithLifecycle()
                val fallbackChat = remember { MutableStateFlow(ChatState()) }
                val chatState by (serviceBinder?.chatState ?: fallbackChat).collectAsStateWithLifecycle()
                val fallbackPreferences = remember { MutableStateFlow(MicrophonePreferenceState()) }
                val microphonePreferences by (serviceBinder?.microphonePreferenceState ?: fallbackPreferences).collectAsStateWithLifecycle()
                val form by viewModel.form.collectAsStateWithLifecycle()
                val chatNotification by viewModel.chatNotificationEntry.destination.collectAsStateWithLifecycle()
                val history by viewModel.history.collectAsStateWithLifecycle()
                val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
                val historyError by viewModel.historyError.collectAsStateWithLifecycle()
                val invitePreview by viewModel.inviteEntry.preview.collectAsStateWithLifecycle()
                val connectedEndpoint = serviceState.bookmarkConnection.takeIf { serviceState.status.phase == ConnectionPhase.CONNECTED }
                val serverInvite = connectedEndpoint?.let { ServerInvite.shareText(it.host, it.port) }
                val channelInvite =
                    connectedEndpoint?.let { endpoint ->
                        serviceState.snapshot.currentChannelId?.let { ServerInvite.shareText(endpoint.host, endpoint.port, it) }
                    }

                MainScreen(
                    form = form,
                    history = history,
                    historyError = historyError,
                    serviceState = serviceState,
                    onHostChanged = viewModel::setHost,
                    onPortChanged = viewModel::setPort,
                    onNicknameChanged = viewModel::setNickname,
                    onPasswordChanged = viewModel::setPassword,
                    onHistorySelected = viewModel::applyHistoryEntry,
                    onHistoryRemoved = viewModel::removeHistoryEntry,
                    onConnect = {
                        viewModel.submitAndRemember(::requestConnection)
                    },
                    onDisconnect = {
                        TeamSpeakService.disconnect(this)
                    },
                    onPlaybackMutedChange = { muted ->
                        serviceBinder?.setPlaybackMuted(muted)
                    },
                    onParticipantMutedChange = { key, muted ->
                        serviceBinder?.setParticipantMuted(key, muted)
                    },
                    onParticipantVolumeChange = { key, volumePercent ->
                        serviceBinder?.setParticipantVolume(key, volumePercent)
                    },
                    onAudioRouteSelected = { routeId ->
                        serviceBinder?.selectAudioRoute(routeId)
                    },
                    onMicrophoneModeChanged = ::setMicrophoneMode,
                    onPushToTalkChanged = ::setPushToTalkPressed,
                    onJoinChannel = { channelId, password ->
                        serviceBinder?.joinChannel(channelId, password, serviceState.bookmarkConnection?.token)
                    },
                    onCopyDiagnostics = diagnosticReports::copy,
                    onSaveDiagnostics = diagnosticReports::save,
                    onMarkAudioIssue = { diagnosticReports.mark { serviceBinder?.markAudioIssue() == true } },
                    onKeepCpuAwakeChanged = { serviceBinder?.setKeepCpuAwake(it) },
                    onRefreshBackgroundRuntime = { serviceBinder?.refreshBackgroundRuntime() },
                    onBatterySettings = ::openBatterySettings,
                    onAppSettings = ::openAppSettings,
                    chatState = chatState,
                    onChatDraft = { serviceBinder?.updateChatDraft(it) },
                    onChatSend = { serviceBinder?.submitText() },
                    onChatVisible = { key, sequence -> serviceBinder?.markChatVisibleThrough(key, sequence) },
                    onOpenPrivateChat = { serviceBinder?.openPrivateChat(it) },
                    onChatConversationSelected = { serviceBinder?.selectChat(it) },
                    onChatConversationClosed = { serviceBinder?.closeChat(it) },
                    onPoke = { recipient, reason -> serviceBinder?.poke(recipient, reason) ?: false },
                    onPokeDismiss = { serviceBinder?.dismissPoke(it) },
                    microphonePreferenceStatus = microphonePreferences.status,
                    onMicrophonePreferenceEdit = { field, settings -> serviceBinder?.editMicrophonePreference(field, settings) },
                    onMicrophonePreferenceCommit = { serviceBinder?.commitMicrophonePreferences() },
                    onMicrophonePreferenceRetry = { serviceBinder?.retryMicrophonePreferences() },
                    onMicrophonePreferenceReset = { serviceBinder?.resetMicrophonePreferences() },
                    bookmarks = bookmarks,
                    canUseBookmarkConnection = serviceBinder != null,
                    onBookmarkSave = viewModel::saveBookmark,
                    onBookmarkSaveFromConnection = { entry, selection ->
                        viewModel.saveBookmarkValidated(entry) { serviceBinder?.validateBookmarkSelection(entry, selection) == true }
                    },
                    onBookmarkRemove = viewModel::removeBookmark,
                    onBookmarkSelect = viewModel::applyBookmark,
                    onBookmarkConnect = { entry ->
                        if (serviceBinder?.state?.value?.status?.phase?.allowsOfflineActions == true) {
                            viewModel.connectBookmark(entry) { requestConnection(it, requireIdle = true) }
                        }
                    },
                    onBookmarkReload = viewModel::reloadBookmarks,
                    onAwayChanged = { away, message -> serviceBinder?.setAway(away, message) },
                    onPresenceRetry = { serviceBinder?.retryPresence() },
                    onResolveUserChannel = { id, uid -> serviceBinder?.resolveUserChannel(id, uid) },
                    onIdentityManagement = {
                        startActivity(
                            Intent(this, io.github.ts3mobile.app.identity.IdentityManagementActivity::class.java),
                        )
                    },
                    onAppearance = { startActivity(Intent(this, io.github.ts3mobile.app.appearance.AppearanceActivity::class.java)) },
                    onOpenRepository = { openProjectRepository(this) },
                    onCheckUpdates = { startActivity(Intent(this, io.github.ts3mobile.app.updates.UpdatesActivity::class.java)) },
                    featureHelpAvailable = serviceBinder != null && invitePreview == null && chatNotification == null,
                    chatNotification = chatNotification,
                    onChatNotificationConsumed = viewModel.chatNotificationEntry::consumed,
                    onUserNavigation = viewModel.chatNotificationEntry::cancel,
                    onNotificationSettings = ::openNotificationSettings,
                    microphoneLevels = serviceBinder?.microphoneLevels ?: remember { emptyFlow() },
                    onShareServerInvite = serverInvite?.let { { shareInvite(it) } },
                    onShareChannelInvite = channelInvite?.let { { shareInvite(it) } },
                    microphoneTrialDialog = serviceBinder?.let { binder -> { dismiss -> MicrophoneTrialDialog(binder, dismiss) } },
                    openChannelFiles =
                        serviceBinder?.takeIf { resources.getBoolean(R.bool.channel_files_probe_enabled) }?.let {
                                binder ->
                            binder::currentChannelFiles
                        },
                    openServerImages =
                        serviceBinder?.takeIf { resources.getBoolean(R.bool.channel_files_probe_enabled) }
                            ?.let { binder -> binder::currentServerImages },
                    openChatAttachment =
                        serviceBinder?.takeIf { resources.getBoolean(R.bool.channel_files_probe_enabled) }
                            ?.let { binder -> binder::openChatAttachment },
                    connectionActions = {
                        PasteInviteAction(
                            enabled =
                                invitePreview == null && serviceState.status.phase.allowsOfflineActions &&
                                    viewModel.pendingConnection == null,
                            onInvite = viewModel.inviteEntry::accept,
                        )
                    },
                )
                invitePreview?.let { preview ->
                    InviteDialog(
                        preview,
                        form.nickname,
                        serviceState.status.phase.takeIf { serviceBinder != null },
                        ready = viewModel.pendingConnection == null,
                        onDismiss = viewModel.inviteEntry::dismiss,
                        onDisconnect = { TeamSpeakService.disconnect(this) },
                    ) { target, nickname, password, channelPassword ->
                        if (viewModel.pendingConnection == null && activityVisible) {
                            viewModel.inviteEntry.confirm(
                                target,
                                serviceBinder?.state?.value?.status?.phase,
                                nickname,
                                password,
                                channelPassword,
                            ) { requestConnection(it, fromInvite = true) }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isBound =
            bindService(
                Intent(this, TeamSpeakService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptEntryIntent(intent)
        resolveChatNotification()
    }

    private fun acceptEntryIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && ServerInvite.SCHEME.equals(intent.scheme, ignoreCase = true)) {
            intent.dataString?.let(viewModel.inviteEntry::accept)
        } else {
            viewModel.chatNotificationEntry.accept(PrivateMessageNotificationIntent.token(intent))
        }
    }

    private fun resolveChatNotification() {
        serviceBinder?.let { viewModel.chatNotificationEntry.resolve(it::consumeChatNotification) }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(PrivateMessageNotifications.settingsIntent(this))
        } catch (_: android.content.ActivityNotFoundException) {
            openAppSettings()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!activityVisible || !autoShowFloatingPtt) return
        val binder = serviceBinder ?: return
        val preparation = binder.connectedPttPreparation ?: return
        ConnectedPttProbeHost(this, preparation, binder.state).arm(leavingMainActivity = true)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(
        intent: Intent,
        requestCode: Int,
        options: Bundle?,
    ) {
        val launchIntent = if (autoShowFloatingPtt) Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION) else intent
        super.startActivityForResult(launchIntent, requestCode, options)
    }

    override fun onPause() {
        activityVisible = false
        serviceBinder?.setAppVisible(false)
        pushToTalkPressed = false
        serviceBinder?.releasePushToTalk()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        serviceBinder?.setAppVisible(true)
        launchPendingConnectionIfVisible()
    }

    override fun onStop() {
        activityVisible = false
        serviceBinder?.setAppVisible(false)
        pushToTalkPressed = false
        serviceBinder?.releasePushToTalk()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            serviceBinder = null
        }
        super.onStop()
    }

    private fun requestConnection(
        config: ServerConfig,
        fromInvite: Boolean = false,
        requireIdle: Boolean = fromInvite,
    ) {
        if (viewModel.pendingConnection != null) return
        if (requireIdle && (!activityVisible || serviceBinder?.state?.value?.status?.phase?.allowsOfflineActions != true)) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.pendingConnection = ConnectionRequest(config, fromInvite, requireIdle)
            viewModel.notificationPermissionResolved = false
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TeamSpeakService.connect(this, config, fromInvite, requireIdle)
        }
    }

    private fun launchPendingConnectionIfVisible() {
        if (!activityVisible || !viewModel.notificationPermissionResolved) return
        val request = viewModel.pendingConnection ?: return
        val phase = serviceBinder?.state?.value?.status?.phase
        if (request.requireIdle && phase == null) return
        viewModel.pendingConnection = null
        if (!request.requireIdle || phase?.allowsOfflineActions == true) {
            TeamSpeakService.connect(this, request.config, request.fromInvite, request.requireIdle)
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * Opens the system page for this app. Android and vendor builds expose their
     * own background and battery policy there (for example the Xiaomi/HyperOS
     * battery policy); the app cannot read or change that policy.
     */
    private fun openAppSettings() {
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun setPushToTalkPressed(pressed: Boolean) {
        pushToTalkPressed = pressed
        if (!pressed) {
            serviceBinder?.setPushToTalkPressed(false)
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            serviceBinder?.setPushToTalkPressed(true)
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setMicrophoneMode(mode: MicrophoneMode) {
        pushToTalkPressed = false
        serviceBinder?.releasePushToTalk()
        if (
            mode.requiresContinuousCapture &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.pendingMicrophoneMode = mode
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.pendingMicrophoneMode = null
            serviceBinder?.setMicrophoneMode(mode)
        }
    }
}
