package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.PokeRecipient
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.UserMessage
import io.github.ts3mobile.app.service.hasPlaybackFailure
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.Ts3Channel

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
    onChannelInfo: (Int) -> Unit = {},
    onChannelInfoDismiss: () -> Unit = {},
    onResolveUserChannel: (Int, String) -> Int? = { _, _ -> null },
) {
    chatState.pokeNotice?.let { PokeNoticeDialog(it, onPokeDismiss) { onPushToTalkChanged(false) } }
    var showPresence by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var joinTarget by androidx.compose.runtime.remember { mutableStateOf<Ts3Channel?>(null) }
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
        SessionSearchDialog(serviceState, onChannelInfo, joinFound, { participant ->
            val channelId = onResolveUserChannel(participant.id, participant.uniqueIdentifier)
            serviceState.snapshot.channels.firstOrNull { it.id == channelId }?.let(joinFound)
        }) { showSearch = false }
    }
    if (serviceState.channelInfo != null && serviceState.status.phase == ConnectionPhase.CONNECTED) {
        ChannelInfoDialog(
            serviceState,
            onChannelInfo,
            joinFound,
            onChannelInfoDismiss,
        )
    }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    if (showBookmarks) {
        BookmarkDialogs(
            bookmarks,
            form,
            canUseBookmarkConnection && serviceState.status.phase in setOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR),
            onBookmarkSave,
            onBookmarkRemove,
            onBookmarkSelect,
            onBookmarkConnect,
            onBookmarkReload,
        ) { showBookmarks = false }
    }
    var showMicrophoneSettings by rememberSaveable { mutableStateOf(false) }
    if (showMicrophoneSettings) {
        MicrophoneSettingsDialog(serviceState, onMicrophoneSettingsChanged) { showMicrophoneSettings = false }
    }
    var showBackgroundRuntime by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text(stringResource(R.string.tab_diagnostics)) },
            text = { Box(Modifier.heightIn(max = 420.dp)) { DiagnosticsPanel(serviceState.diagnostics, onCopyDiagnostics) } },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    if (showBackgroundRuntime) {
        BackgroundRuntimeDialog(
            state = serviceState.backgroundRuntime,
            onKeepCpuAwakeChanged = onKeepCpuAwakeChanged,
            onBatterySettings = onBatterySettings,
            onAppSettings = onAppSettings,
            onCopyDiagnostics = onCopyDiagnostics,
            onDismiss = { showBackgroundRuntime = false },
        )
    }
    val largeText = LocalDensity.current.fontScale > 1.3f
    var chatSelected by androidx.compose.runtime.remember { mutableStateOf(false) }
    val chatKeyboard = chatSelected && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Scaffold(
        topBar = {
            if (!chatKeyboard) {
                MainScreenAppBar(
                    serviceState.status.phase,
                    largeText,
                    { onPushToTalkChanged(false) },
                    listOf(
                        MainMenuAction(
                            R.string.presence_title,
                            serviceState.status.phase == ConnectionPhase.CONNECTED,
                        ) { showPresence = true },
                        MainMenuAction(
                            R.string.discovery_title,
                            serviceState.status.phase == ConnectionPhase.CONNECTED,
                        ) { showSearch = true },
                        MainMenuAction(R.string.bookmarks_title) { showBookmarks = true },
                        MainMenuAction(R.string.microphone_settings) { showMicrophoneSettings = true },
                        MainMenuAction(R.string.background_runtime_title) {
                            onRefreshBackgroundRuntime()
                            showBackgroundRuntime = true
                        },
                        MainMenuAction(R.string.tab_diagnostics) { showDiagnostics = true },
                    ),
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
                    onChannelInfo = onChannelInfo,
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
                    onOpenBookmarks = { showBookmarks = true },
                )
            }
        }
    }
}
