package io.github.ts3mobile.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Density
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.bookmarks.BookmarkCodec
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.app.bookmarks.bookmarkContext
import io.github.ts3mobile.app.bookmarks.withSelectedBookmark
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatNotificationDestination
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.app.discovery.resolveParticipantChannel
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.app.service.ParticipantAudioSettings
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.app.withSelectedHistoryEntry
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ControlOutcome
import io.github.ts3mobile.protocol.ControlStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import io.github.ts3mobile.protocol.Ts3TextEvent
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

internal class UiFixture(connected: Boolean = false) {
    val bookmarks = mutableStateOf(BookmarkState(loading = false))
    val bookmarkConnections = mutableListOf<ServerBookmark>()
    val awayRequests = mutableListOf<Pair<Boolean, String>>()
    val chatStore = ChatStore()
    val chat = mutableStateOf(ChatState())
    val chatNotification = mutableStateOf<ChatNotificationDestination?>(null)
    var notificationSettings = 0
    val microphoneLevels = MutableStateFlow<MicrophoneLevel?>(null)
    val sentBodies = mutableListOf<String>()
    val sentTargets = mutableListOf<TextTarget>()
    val sentPokes = mutableListOf<Pair<TextTarget.Private, String>>()
    var sendOutcome: TextSendOutcome = TextSendOutcome.Accepted

    init {
        if (connected) {
            chatStore.begin(1)
            chatStore.bind(1, 1, ChatBinding(1, 1, 1, 1), participants = connectedState().snapshot.participants)
            refreshChat()
        }
    }

    fun refreshChat() {
        chat.value = chatStore.state.value
    }

    fun receiveText(body: String) {
        chatStore.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, null, 2, "fixture-uid", "Sam", body))
        refreshChat()
    }

    fun receivePoke(body: String) {
        chatStore.receivePoke(1, 1, Ts3PokeEvent(2, "fixture-2", "Sam", body))
        refreshChat()
    }

    fun receivePrivate(
        body: String,
        clientId: Int = 2,
    ) {
        val peer = state.value.snapshot.participants.single { it.id == clientId }
        chatStore.receive(1, 1, Ts3TextEvent(TextTargetMode.PRIVATE, 1, peer.id, peer.uniqueIdentifier, peer.nickname, body))
        refreshChat()
    }

    val form = mutableStateOf(ConnectionFormState(host = "voice.example.com", nickname = "Alex"))
    val state = mutableStateOf(if (connected) connectedState() else TeamSpeakServiceState())
    val history = mutableStateOf<List<ServerHistoryEntry>>(emptyList())
    val historyError = mutableStateOf(false)
    val removedHistory = mutableListOf<ServerHistoryEntry>()
    val keepCpuAwakeRequests = mutableListOf<Boolean>()
    var refreshes = 0
    var batterySettings = 0
    var appSettings = 0
    var connects = 0
    var disconnects = 0
    var copies = 0
    var diagnosticSaves = 0
    var audioMarks = 0
    var repositoryOpens = 0
    var updateChecks = 0
    val microphonePreferenceStatus = mutableStateOf(MicrophonePreferenceStatus.SAVED)
    var microphonePreferenceRetries = 0
    var microphonePreferenceCommits = 0
    val joins = mutableListOf<Pair<Int, String>>()
    val transmission = mutableListOf<Boolean>()

    @Composable
    fun Render(
        dark: Boolean = false,
        locale: String = "en",
        fontScale: Float = 1f,
        saveBookmark: (
            ServerBookmark,
        ) -> Unit = { bookmarks.value = BookmarkState(BookmarkCodec.save(bookmarks.value.entries, it), loading = false) },
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val base = LocalContext.current
        val originalConfig = remember(base) { Configuration(base.resources.configuration) }
        DisposableEffect(base) {
            onDispose {
                keyboard?.hide()
                @Suppress("DEPRECATION")
                base.resources.updateConfiguration(originalConfig, base.resources.displayMetrics)
            }
        }
        val config =
            remember(locale, fontScale) {
                Configuration(base.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(locale))
                    this.fontScale = fontScale
                }
            }
        val context =
            remember(config) {
                // Popup/Dialog obtain resources from their Android view context.
                // Align that test-only context too, so the phone locale cannot leak in.
                @Suppress("DEPRECATION")
                base.resources.updateConfiguration(config, base.resources.displayMetrics)
                base.createConfigurationContext(config)
            }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides config,
            LocalDensity provides Density(LocalDensity.current.density, fontScale),
        ) {
            Ts3MobileTheme(darkTheme = dark) {
                MainScreen(
                    onCheckUpdates = { updateChecks++ },
                    form = form.value,
                    history = history.value,
                    historyError = historyError.value,
                    serviceState = state.value,
                    onHostChanged = { form.value = form.value.copy(host = it) },
                    onPortChanged = { form.value = form.value.copy(port = it) },
                    onNicknameChanged = { form.value = form.value.copy(nickname = it) },
                    onPasswordChanged = { form.value = form.value.copy(password = it) },
                    onHistorySelected = { form.value = form.value.withSelectedHistoryEntry(it) },
                    onHistoryRemoved = { entry ->
                        removedHistory += entry
                        history.value = history.value.filterNot { it.matches(entry) }
                    },
                    onConnect = {
                        connects++
                        form.value = form.value.copy(submitted = true)
                    },
                    onDisconnect = { disconnects++ },
                    onPlaybackMutedChange = { state.value = state.value.copy(playbackMuted = it) },
                    onParticipantMutedChange = { key, muted ->
                        val settings = state.value.participantAudioSettings[key] ?: ParticipantAudioSettings()
                        state.value =
                            state.value.copy(
                                participantAudioSettings = state.value.participantAudioSettings + (key to settings.copy(muted = muted)),
                            )
                    },
                    onParticipantVolumeChange = { key, volume ->
                        val settings = state.value.participantAudioSettings[key] ?: ParticipantAudioSettings()
                        state.value =
                            state.value.copy(
                                participantAudioSettings =
                                    state.value.participantAudioSettings +
                                        (key to settings.copy(volumePercent = volume)),
                            )
                    },
                    onAudioRouteSelected = {},
                    onMicrophoneModeChanged = { state.value = state.value.copy(microphoneMode = it) },
                    onPushToTalkChanged = { transmission += it },
                    onJoinChannel = { id, password -> joins += id to password },
                    onCopyDiagnostics = { copies++ },
                    onSaveDiagnostics = { diagnosticSaves++ },
                    onMarkAudioIssue = { audioMarks++ },
                    onKeepCpuAwakeChanged = {
                        keepCpuAwakeRequests += it
                        state.value = state.value.copy(backgroundRuntime = state.value.backgroundRuntime.copy(keepCpuAwake = it))
                    },
                    onRefreshBackgroundRuntime = { refreshes++ },
                    onBatterySettings = { batterySettings++ },
                    onAppSettings = { appSettings++ },
                    chatState = chat.value,
                    chatNotification = chatNotification.value,
                    onChatNotificationConsumed = { if (chatNotification.value?.requestId == it) chatNotification.value = null },
                    onUserNavigation = { chatNotification.value = null },
                    onNotificationSettings = { notificationSettings++ },
                    microphoneLevels = microphoneLevels,
                    onChatDraft = {
                        chatStore.updateDraft(it)
                        refreshChat()
                    },
                    onChatVisible = { key, sequence ->
                        chatStore.markVisibleThrough(key, sequence)
                        refreshChat()
                    },
                    onOpenPrivateChat = {
                        chatStore.openPrivate(it)
                        refreshChat()
                    },
                    onChatConversationSelected = {
                        chatStore.select(it)
                        refreshChat()
                    },
                    onChatConversationClosed = {
                        chatStore.close(it)
                        refreshChat()
                    },
                    onChatSend = {
                        chat.value.binding?.let { binding ->
                            chatStore.claimSend(binding)?.let {
                                sentBodies += it.message.body
                                sentTargets += it.target
                                chatStore.complete(binding, it.message.id, sendOutcome)
                            }
                        }
                        refreshChat()
                    },
                    onPoke = { recipient, reason ->
                        val pending = chat.value.binding?.let { chatStore.claimPoke(it, recipient, reason) }
                        if (pending != null) {
                            sentPokes += recipient.target to reason
                            chatStore.complete(pending.binding, pending.message.id, sendOutcome)
                        }
                        refreshChat()
                        pending != null
                    },
                    bookmarks = bookmarks.value,
                    onBookmarkSave = saveBookmark,
                    onBookmarkSaveFromConnection = { entry, selection ->
                        if (state.value.bookmarkContext()?.accepts(entry, selection) == true) {
                            saveBookmark(entry)
                        } else {
                            bookmarks.value = bookmarks.value.copy(failed = true, contextChanged = true)
                        }
                    },
                    onBookmarkRemove = {
                        bookmarks.value =
                            BookmarkState(
                                BookmarkCodec.remove(
                                    bookmarks.value.entries,
                                    it,
                                ),
                                loading = false,
                            )
                    },
                    onBookmarkSelect = { form.value = form.value.withSelectedBookmark(it) },
                    onBookmarkConnect = {
                        bookmarkConnections += it
                        form.value = form.value.withSelectedBookmark(it)
                    },
                    onBookmarkReload = {},
                    onAwayChanged = { away, message ->
                        awayRequests += away to message
                        state.value =
                            state.value.copy(
                                presence =
                                    state.value.presence.copy(
                                        away = away,
                                        awayMessage = message,
                                        outcome = ControlOutcome(ControlStatus.ACCEPTED),
                                    ),
                            )
                    },
                    onPresenceRetry = {},
                    onResolveUserChannel = { id, uid -> resolveParticipantChannel(state.value.snapshot, id, uid) },
                    onOpenRepository = { repositoryOpens++ },
                    onMicrophoneSettingsChanged = { state.value = state.value.copy(microphoneSettings = it) },
                    microphonePreferenceStatus = microphonePreferenceStatus.value,
                    onMicrophonePreferenceCommit = { microphonePreferenceCommits++ },
                    onMicrophonePreferenceRetry = { microphonePreferenceRetries++ },
                    onMicrophonePreferenceReset = { state.value = state.value.copy(microphoneSettings = MicrophoneSettings()) },
                    onPokeDismiss = {
                        chatStore.dismissPoke(it)
                        refreshChat()
                    },
                )
            }
        }
    }
}

private fun connectedState() =
    TeamSpeakServiceState(
        status = ConnectionStatus(ConnectionPhase.CONNECTED),
        serverLabel = "The Common Room",
        identityReady = true,
        snapshot =
            SessionSnapshot(
                channels =
                    listOf(
                        Ts3Channel(1, 0, 0, "Lobby", 3, false, true),
                        Ts3Channel(2, 0, 1, "Design studio", 1, false, false),
                        Ts3Channel(3, 0, 2, "Private room", 0, true, false),
                        Ts3Channel(4, 2, 0, "After hours", 0, false, false),
                    ),
                participants =
                    listOf(
                        Ts3Participant(1, 1, "Alex", false, false, false, uniqueIdentifier = "fixture-1"),
                        Ts3Participant(2, 1, "Sam", true, false, false, uniqueIdentifier = "fixture-2"),
                        Ts3Participant(3, 1, "Jamie", false, true, false, uniqueIdentifier = "fixture-3"),
                        Ts3Participant(4, 2, "Morgan", false, false, false, uniqueIdentifier = "fixture-4"),
                    ),
                ownClientId = 1,
            ),
    )
