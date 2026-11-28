package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatNotificationDestination
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.PokeRecipient
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ServerImages
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.boundedText

@Composable
internal fun ConnectedContent(
    state: TeamSpeakServiceState,
    onDisconnect: () -> Unit,
    onPlaybackMutedChange: (Boolean) -> Unit,
    onParticipantMutedChange: (String, Boolean) -> Unit,
    onParticipantVolumeChange: (String, Int) -> Unit,
    onAudioRouteSelected: (Int) -> Unit,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
    onPushToTalkChanged: (Boolean) -> Unit,
    onJoinChannel: (Int, String) -> Unit,
    chat: ChatState = ChatState(),
    onChatDraft: (String) -> Unit = {},
    onChatSend: () -> Unit = {},
    onChatVisible: (ChatConversationKey, Long) -> Unit = { _, _ -> },
    onOpenPrivateChat: (Int) -> Unit = {},
    onChatConversationSelected: (ChatConversationKey) -> Unit = {},
    onChatConversationClosed: (ChatConversationKey) -> Unit = {},
    onChatSelected: (Boolean) -> Unit = {},
    onPoke: (PokeRecipient, String) -> Boolean = { _, _ -> false },
    onOpenSearch: () -> Unit = {},
    onOpenPresence: () -> Unit = {},
    chatNotification: ChatNotificationDestination? = null,
    onChatNotificationConsumed: (String) -> Unit = {},
    onUserNavigation: () -> Unit = {},
    microphoneVolumeShortcut: @Composable () -> Unit = {},
    openServerImages: ((String) -> ServerImages?)? = null,
    openChatAttachment: OpenChatAttachment? = null,
    guideNavigation: GuideNavigation? = null,
) {
    ChatSessionState(chat) { conversations ->
        var selectedTab by rememberSaveable { mutableStateOf(SessionTab.CHANNELS) }
        var notificationPicker by rememberSaveable { mutableStateOf(false) }
        var notificationSelection by rememberSaveable { mutableStateOf<Long?>(null) }
        ChatNotificationNavigationEffect(chatNotification, chat, onChatConversationSelected, { picker ->
            onPushToTalkChanged(false)
            selectedTab = SessionTab.CHAT
            notificationPicker = picker
        }, onChatNotificationConsumed)
        LaunchedEffect(chat.selected, notificationSelection) {
            if (notificationSelection != null && chat.active.id == notificationSelection) {
                notificationPicker = false
                notificationSelection = null
            }
        }
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val compactHeader = selectedTab == SessionTab.CHAT && LocalConfiguration.current.screenHeightDp < 700
        val recovering = state.status.phase != ConnectionPhase.CONNECTED
        val chatAttachments =
            if (!recovering && selectedTab == SessionTab.CHAT && !notificationPicker) {
                rememberChatAttachmentActions(state, chat.binding, openChatAttachment)
            } else {
                null
            }
        val imageConnectionToken = state.bookmarkConnection?.token
        val currentOpenServerImages by rememberUpdatedState(openServerImages)
        val images =
            if (!recovering && imageConnectionToken != null && openServerImages != null) {
                remember(imageConnectionToken, chat.binding?.attempt) {
                    ServerImagePreviewScope { currentOpenServerImages?.invoke(imageConnectionToken) }
                }.also { it.Show(state.snapshot) }
            } else {
                null
            }
        var pokeRecipient by remember { mutableStateOf<PokeRecipient?>(null) }
        val openPoke: (Ts3Participant) -> Unit = { participant ->
            onPushToTalkChanged(false)
            pokeRecipient =
                PokeRecipient(
                    chat.binding,
                    TextTarget.Private(participant.id, participant.uniqueIdentifier),
                    participant.nickname.boundedText(256),
                )
        }
        pokeRecipient?.let { recipient ->
            PokeSendDialog(recipient, chat, state.snapshot.participants, onPoke, { pokeRecipient = null })
        }
        DisposableEffect(selectedTab) { onDispose { onPushToTalkChanged(false) } }
        LaunchedEffect(selectedTab) { onChatSelected(selectedTab == SessionTab.CHAT) }
        Column(Modifier.fillMaxSize().then(if (selectedTab == SessionTab.CHAT) Modifier.imePadding() else Modifier)) {
            if (!keyboardOpen || selectedTab != SessionTab.CHAT) {
                SessionHeader(state, onDisconnect, onPlaybackMutedChange, onAudioRouteSelected, compactHeader, {
                    onPushToTalkChanged(false)
                    onOpenSearch()
                }, {
                    onPushToTalkChanged(false)
                    onOpenPresence()
                })
            }
            if (!keyboardOpen || selectedTab != SessionTab.CHAT) {
                NavigationTabs(selectedTab, {
                    onUserNavigation()
                    notificationPicker = false
                    notificationSelection = null
                    onPushToTalkChanged(false)
                    selectedTab = it
                    guideNavigation?.onTabSelected?.invoke(it)
                }, chat.totalUnread, guidedTab = guideNavigation?.route?.tab)
            }
            if (selectedTab == SessionTab.CHANNELS && !recovering) {
                SectionLabel(stringResource(R.string.channel_gesture_hint), Modifier.padding(horizontal = 20.dp))
            }
            Panel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.medium)) {
                    when (selectedTab) {
                        SessionTab.CHANNELS ->
                            if (recovering) {
                                Text(
                                    stringResource(R.string.chat_recovering_lists),
                                )
                            } else {
                                ChannelList(state, onJoinChannel, openPoke, images)
                            }
                        SessionTab.PARTICIPANTS ->
                            if (recovering) {
                                Text(stringResource(R.string.chat_recovering_lists))
                            } else {
                                ParticipantList(state, onParticipantMutedChange, onParticipantVolumeChange, {
                                    onPushToTalkChanged(false)
                                    onOpenPrivateChat(it)
                                    onUserNavigation()
                                    selectedTab = SessionTab.CHAT
                                }, openPoke, images)
                            }
                        SessionTab.CHAT ->
                            if (notificationPicker) {
                                ChatNotificationConversationList(chat) { id, key ->
                                    onUserNavigation()
                                    notificationSelection = id
                                    onChatConversationSelected(key)
                                }
                            } else {
                                ChatConversationContent(
                                    chat,
                                    conversations,
                                    onChatDraft,
                                    onChatSend,
                                    onChatVisible,
                                    { onPushToTalkChanged(false) },
                                    {
                                        onUserNavigation()
                                        onChatConversationSelected(it)
                                    },
                                    onChatConversationClosed,
                                    chatAttachments,
                                )
                            }
                    }
                }
            }
            if (selectedTab == SessionTab.CHAT) {
                CompactChatVoiceBar(state, onMicrophoneModeChanged, onPushToTalkChanged, microphoneVolumeShortcut)
            } else {
                MicrophoneControl(
                    mode = state.microphoneMode,
                    isTransmitting = state.isTransmitting,
                    isCapturing = state.isCapturing,
                    captureDeferred = state.microphoneError == io.github.ts3mobile.app.service.UserMessage.MicrophoneResumeInApp,
                    onMicrophoneModeChanged = onMicrophoneModeChanged,
                    onPushToTalkChanged = onPushToTalkChanged,
                    microphoneVolumeShortcut = microphoneVolumeShortcut,
                )
            }
        }
    }
}
