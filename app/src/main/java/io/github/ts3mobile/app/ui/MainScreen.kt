package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkChannelSelection
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.bookmarks.bookmarkContext
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatNotificationDestination
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.PokeRecipient
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.UserMessage
import io.github.ts3mobile.app.service.hasPlaybackFailure
import io.github.ts3mobile.app.settings.MicrophonePreferenceField
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.Ts3Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    form: ConnectionFormState,
    history: List<ServerHistoryEntry>,
    historyError: Boolean,
    serviceState: TeamSpeakServiceState,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onHistorySelected: (ServerHistoryEntry) -> Unit,
    onHistoryRemoved: (ServerHistoryEntry) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onPlaybackMutedChange: (Boolean) -> Unit,
    onParticipantMutedChange: (String, Boolean) -> Unit,
    onParticipantVolumeChange: (String, Int) -> Unit,
    onAudioRouteSelected: (Int) -> Unit,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
    onPushToTalkChanged: (Boolean) -> Unit,
    onJoinChannel: (Int, String) -> Unit,
    onCopyDiagnostics: () -> Unit,
    onKeepCpuAwakeChanged: (Boolean) -> Unit,
    onRefreshBackgroundRuntime: () -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
    chatState: ChatState = ChatState(),
    onChatDraft: (String) -> Unit = {},
    onChatSend: () -> Unit = {},
    onChatVisible: (ChatConversationKey, Long) -> Unit = { _, _ -> },
    onOpenPrivateChat: (Int) -> Unit = {},
    onChatConversationSelected: (ChatConversationKey) -> Unit = {},
    onChatConversationClosed: (ChatConversationKey) -> Unit = {},
    onPoke: (PokeRecipient, String) -> Boolean = { _, _ -> false },
    onPokeDismiss: (Long) -> Unit = {},
    onMicrophoneSettingsChanged: (MicrophoneSettings) -> Unit = {},
    bookmarks: BookmarkState = BookmarkState(),
    canUseBookmarkConnection: Boolean = true,
    onBookmarkSave: (ServerBookmark) -> Unit = {},
    onBookmarkRemove: (ServerBookmark) -> Unit = {},
    onBookmarkSelect: (ServerBookmark) -> Unit = {},
    onBookmarkConnect: (ServerBookmark) -> Unit = {},
    onBookmarkReload: () -> Unit = {},
    onAwayChanged: (Boolean, String) -> Unit = { _, _ -> },
    onPresenceRetry: () -> Unit = {},
    onResolveUserChannel: (Int, String) -> Int? = { _, _ -> null },
    onOpenRepository: () -> Unit = {},
    onSaveDiagnostics: () -> Unit = {},
    onMarkAudioIssue: () -> Unit = {},
    microphonePreferenceStatus: MicrophonePreferenceStatus = MicrophonePreferenceStatus.SAVED,
    onMicrophonePreferenceEdit: (
        MicrophonePreferenceField,
        MicrophoneSettings,
    ) -> Unit = { _, value -> onMicrophoneSettingsChanged(value) },
    onMicrophonePreferenceCommit: () -> Unit = {},
    onMicrophonePreferenceRetry: () -> Unit = {},
    onMicrophonePreferenceReset: () -> Unit = {},
    onBookmarkSaveFromConnection: (ServerBookmark, BookmarkChannelSelection) -> Unit = { entry, _ -> onBookmarkSave(entry) },
    chatNotification: ChatNotificationDestination? = null,
    onChatNotificationConsumed: (String) -> Unit = {},
    onUserNavigation: () -> Unit = {},
    onNotificationSettings: () -> Unit = {},
    microphoneLevels: Flow<MicrophoneLevel?> = emptyFlow(),
    onIdentityManagement: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onCheckUpdates: () -> Unit = {},
    connectionActions: @Composable () -> Unit = {},
    onShareServerInvite: (() -> Unit)? = null,
    onShareChannelInvite: (() -> Unit)? = null,
    microphoneTrialDialog: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    openChannelFiles: (() -> io.github.ts3mobile.protocol.ChannelFiles?)? = null,
    openServerImages: ((String) -> io.github.ts3mobile.protocol.ServerImages?)? = null,
    openChatAttachment: OpenChatAttachment? = null,
    featureHelpAvailable: Boolean = true,
) {
    val snackbar = remember { SnackbarHostState() }
    var guideVisit by rememberSaveable { mutableStateOf<GuideRoute?>(null) }
    var pendingHelpTitle by rememberSaveable { mutableStateOf<Int?>(null) }
    val fileChannelId =
        serviceState.snapshot.currentChannelId.takeIf {
            serviceState.status.phase == ConnectionPhase.CONNECTED && serviceState.switchingChannelId == null
        }
    var fileBrowser by remember(serviceState.bookmarkConnection?.token, fileChannelId) {
        mutableStateOf<io.github.ts3mobile.protocol.ChannelFiles?>(null)
    }
    fileBrowser?.let { files ->
        val closeFiles = {
            fileBrowser = null
            guideVisit = null
        }
        GuideDestinationTip(guideVisit.takeIf { it == GuideRoute.FILES }) { ChannelFilesDialog(files, closeFiles) }
    }
    PokeFeedbackEffect(chatState, snackbar)
    chatState.pokeNotice?.let { PokeNoticeDialog(it, onPokeDismiss) { onPushToTalkChanged(false) } }
    var showPresence by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val pages = rememberSaveableStateHolder()
    LaunchedEffect(chatNotification) {
        if (chatNotification != null) {
            showSettings = false
            guideVisit = null
        }
    }
    val connected = serviceState.status.phase == ConnectionPhase.CONNECTED
    var showInviteShare by remember(serviceState.bookmarkConnection?.token, connected) { mutableStateOf(false) }
    if (showInviteShare && onShareServerInvite != null) {
        ShareInviteDialog(onShareServerInvite, onShareChannelInvite) { showInviteShare = false }
    }
    var joinTarget by remember(serviceState.bookmarkConnection?.token) { mutableStateOf<Ts3Channel?>(null) }
    LaunchedEffect(serviceState.status.phase) {
        if (serviceState.status.phase != ConnectionPhase.CONNECTED) {
            showPresence = false
            showSearch = false
            joinTarget = null
        }
    }
    val joinFound: (Ts3Channel) -> Unit = { channel ->
        if (channel.hasPassword) joinTarget = channel else onJoinChannel(channel.id, "")
    }
    joinTarget?.let {
            channel ->
        ChannelPasswordDialog(channel.name, { joinTarget = null }) { password ->
            onJoinChannel(channel.id, password)
            joinTarget = null
        }
    }
    if (showPresence) PresenceDialog(serviceState, onAwayChanged, onPresenceRetry) { showPresence = false }
    if (showSearch) {
        SessionSearchDialog(serviceState, joinFound, { participant ->
            val channelId = onResolveUserChannel(participant.id, participant.uniqueIdentifier)
            serviceState.snapshot.channels.firstOrNull { it.id == channelId }?.let(joinFound)
        }) { showSearch = false }
    }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    if (showBookmarks) {
        val closeBookmarks = {
            showBookmarks = false
            guideVisit = null
        }
        GuideDestinationTip(guideVisit.takeIf { it == GuideRoute.BOOKMARKS }) {
            BookmarkDialogs(
                bookmarks,
                form,
                canUseBookmarkConnection && serviceState.status.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR),
                onBookmarkSave,
                onBookmarkRemove,
                onBookmarkSelect,
                onBookmarkConnect,
                onBookmarkReload,
                serviceState.bookmarkContext(),
                onBookmarkSaveFromConnection,
                onDismiss = closeBookmarks,
            )
        }
    }
    if (showSettings) {
        val closeSettings = {
            showSettings = false
            guideVisit = null
        }
        AppSettings(
            state = serviceState,
            snackbar = snackbar,
            onBack = closeSettings,
            onMicrophonePreferenceEdit = onMicrophonePreferenceEdit,
            microphonePreferenceStatus = microphonePreferenceStatus,
            onMicrophonePreferenceCommit = onMicrophonePreferenceCommit,
            onMicrophonePreferenceRetry = onMicrophonePreferenceRetry,
            onMicrophonePreferenceReset = onMicrophonePreferenceReset,
            microphoneLevels = microphoneLevels,
            onPushToTalkChanged = onPushToTalkChanged,
            onMicrophoneModeChanged = onMicrophoneModeChanged,
            microphoneTrialDialog = microphoneTrialDialog,
            onNotificationSettings = onNotificationSettings,
            onAppearance = onAppearance,
            onIdentityManagement = onIdentityManagement,
            onRefreshBackgroundRuntime = onRefreshBackgroundRuntime,
            onKeepCpuAwakeChanged = onKeepCpuAwakeChanged,
            onBatterySettings = onBatterySettings,
            onAppSettings = onAppSettings,
            onCopyDiagnostics = onCopyDiagnostics,
            onSaveDiagnostics = onSaveDiagnostics,
            onMarkAudioIssue = onMarkAudioIssue,
            onCheckUpdates = onCheckUpdates,
            onOpenRepository = onOpenRepository,
            guideRoute = guideVisit,
            onHelpTopicRequested = { title ->
                pendingHelpTitle = title
                closeSettings()
            },
        )
        return
    }
    pages.SaveableStateProvider("main") {
        val anchors = remember { GuideAnchors() }
        val canShowHelp =
            featureHelpAvailable && !showBookmarks && !showPresence && !showSearch && !showInviteShare && joinTarget == null &&
                fileBrowser == null && chatState.pokeNotice == null &&
                serviceState.status.phase in setOf(ConnectionPhase.CONNECTED, ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR)
        CompositionLocalProvider(LocalGuideAnchors provides anchors) {
            MainFeatureHelp(
                canShowHelp,
                connected,
                anchors,
                filesAvailable = fileChannelId != null && openChannelFiles != null,
                requestedTitle = pendingHelpTitle,
                onTopicOpened = { pendingHelpTitle = null },
            ) { navigation ->
                val expectedRoute = navigation?.route
                val recordGuideVisit: (GuideRoute?) -> Unit = { route ->
                    guideVisit = route
                    if (route != null) navigation?.onDestinationOpened?.invoke()
                }
                val openBookmarks = {
                    recordGuideVisit(expectedRoute.takeIf { it == GuideRoute.BOOKMARKS })
                    showBookmarks = true
                }
                val largeText = LocalDensity.current.fontScale > 1.3f
                var chatSelected by androidx.compose.runtime.remember { mutableStateOf(false) }
                val chatKeyboard = chatSelected && WindowInsets.ime.getBottom(LocalDensity.current) > 0
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        if (!chatKeyboard) {
                            MainScreenAppBar(
                                serviceState.status.phase,
                                largeText,
                                {
                                    onUserNavigation()
                                    onPushToTalkChanged(false)
                                },
                                listOfNotNull(
                                    if (connected && onShareServerInvite != null) {
                                        MainMenuAction(R.string.invite_share_title, UiIcon.Connect) { showInviteShare = true }
                                    } else {
                                        null
                                    },
                                    if (connected) MainMenuAction(R.string.presence_title, UiIcon.Away) { showPresence = true } else null,
                                    if (connected) MainMenuAction(R.string.discovery_title, UiIcon.Search) { showSearch = true } else null,
                                    if (openChannelFiles != null && fileChannelId != null) {
                                        MainMenuAction(
                                            R.string.channel_files_probe_title,
                                            UiIcon.Folder,
                                        ) {
                                            fileBrowser = openChannelFiles()
                                            recordGuideVisit(expectedRoute.takeIf { it == GuideRoute.FILES && fileBrowser != null })
                                        }
                                    } else {
                                        null
                                    },
                                    MainMenuAction(R.string.bookmarks_title, UiIcon.Bookmark, action = openBookmarks),
                                    MainMenuAction(R.string.settings_title, UiIcon.Settings, startsGroup = true) {
                                        recordGuideVisit(expectedRoute.takeIf { it?.settingsEntry != null })
                                        showSettings = true
                                    },
                                ),
                                guidedAction = expectedRoute?.menuLabel,
                            )
                        }
                    },
                ) { contentPadding ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding),
                    ) {
                        val context = LocalContext.current
                        val statusText = serviceState.resolveStatusText(context)
                        if (serviceState.status.phase == ConnectionPhase.CONNECTED) {
                            statusText?.let { detail -> StatusMessage(serviceState.status.phase, detail) }
                        }
                        serviceState.microphoneError?.let { message ->
                            StatusMessage(ConnectionPhase.ERROR, message.resolve(context))
                        }
                        if (serviceState.hasPlaybackFailure) {
                            StatusMessage(ConnectionPhase.ERROR, stringResource(R.string.playback_failed))
                        }
                        serviceState.channelError?.let { message ->
                            StatusMessage(ConnectionPhase.ERROR, message.resolve(context))
                        }
                        serviceState.audioRouting.error?.let { error ->
                            StatusMessage(ConnectionPhase.ERROR, UserMessage.AudioRouting(error).resolve(context))
                        }

                        val showRecoveringChat = chatState.established && chatState.scope != null && !chatState.connected
                        if (serviceState.status.phase == ConnectionPhase.CONNECTED || showRecoveringChat) {
                            ConnectedContent(
                                state = serviceState,
                                onDisconnect = onDisconnect,
                                onPlaybackMutedChange = onPlaybackMutedChange,
                                onParticipantMutedChange = onParticipantMutedChange,
                                onParticipantVolumeChange = onParticipantVolumeChange,
                                onAudioRouteSelected = onAudioRouteSelected,
                                onMicrophoneModeChanged = onMicrophoneModeChanged,
                                onPushToTalkChanged = onPushToTalkChanged,
                                onJoinChannel = onJoinChannel,
                                chat = chatState,
                                onChatDraft = onChatDraft,
                                onChatSend = onChatSend,
                                onChatVisible = onChatVisible,
                                onOpenPrivateChat = onOpenPrivateChat,
                                onChatConversationSelected = onChatConversationSelected,
                                onChatConversationClosed = onChatConversationClosed,
                                onChatSelected = { chatSelected = it },
                                onPoke = onPoke,
                                onOpenSearch = { showSearch = true },
                                onOpenPresence = { showPresence = true },
                                chatNotification = chatNotification,
                                onChatNotificationConsumed = onChatNotificationConsumed,
                                onUserNavigation = onUserNavigation,
                                openServerImages = openServerImages,
                                openChatAttachment = openChatAttachment,
                                guideNavigation = navigation,
                                microphoneVolumeShortcut = {
                                    MicrophoneVolumeShortcut(
                                        volumePercent = serviceState.microphoneSettings.volumePercent,
                                        preferenceStatus = microphonePreferenceStatus,
                                        onVolumeChanged = {
                                            onMicrophonePreferenceEdit(
                                                MicrophonePreferenceField.VOLUME,
                                                serviceState.microphoneSettings.copy(volumePercent = it),
                                            )
                                        },
                                        onCommit = onMicrophonePreferenceCommit,
                                        onRetry = onMicrophonePreferenceRetry,
                                        onOpen = { onPushToTalkChanged(false) },
                                    )
                                },
                            )
                        } else {
                            ConnectionForm(
                                form = form,
                                history = history,
                                historyError = historyError,
                                phase = serviceState.status.phase,
                                onHostChanged = onHostChanged,
                                onPortChanged = onPortChanged,
                                onNicknameChanged = onNicknameChanged,
                                onPasswordChanged = onPasswordChanged,
                                onHistorySelected = onHistorySelected,
                                onHistoryRemoved = onHistoryRemoved,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                                statusText = statusText,
                                onOpenBookmarks = openBookmarks,
                                actions = connectionActions,
                            )
                        }
                    }
                }
            }
        }
    }
}
