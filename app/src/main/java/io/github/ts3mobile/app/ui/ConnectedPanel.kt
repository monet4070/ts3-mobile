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
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.PokeRecipient
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.TextTarget
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
    onChannelInfo: (Int) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(SessionTab.CHANNELS) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val compactHeader = selectedTab == SessionTab.CHAT && LocalConfiguration.current.screenHeightDp < 700
    val recovering = state.status.phase != ConnectionPhase.CONNECTED
    var pokeRecipient by remember { mutableStateOf<PokeRecipient?>(null) }
    pokeRecipient?.let { recipient ->
        PokeSendDialog(recipient, chat, state.snapshot.participants, { captured, reason ->
            onPoke(captured, reason).also { accepted -> if (accepted) selectedTab = SessionTab.CHAT }
        }, { pokeRecipient = null })
    }
    DisposableEffect(selectedTab) { onDispose { onPushToTalkChanged(false) } }
    LaunchedEffect(selectedTab) { onChatSelected(selectedTab == SessionTab.CHAT) }
    Column(Modifier.fillMaxSize().then(if (selectedTab == SessionTab.CHAT) Modifier.imePadding() else Modifier)) {
        if (!keyboardOpen || selectedTab != SessionTab.CHAT) {
            SessionHeader(state, onDisconnect, onPlaybackMutedChange, onAudioRouteSelected, compactHeader)
        }
        if (!keyboardOpen || selectedTab != SessionTab.CHAT) {
            NavigationTabs(selectedTab, {
                onPushToTalkChanged(false)
                selectedTab = it
            }, chat.totalUnread)
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
                            ChannelList(state, onJoinChannel, onChannelInfo)
                        }
                    SessionTab.PARTICIPANTS ->
                        if (recovering) {
                            Text(stringResource(R.string.chat_recovering_lists))
                        } else {
                            ParticipantList(state, onParticipantMutedChange, onParticipantVolumeChange, {
                                onPushToTalkChanged(false)
                                onOpenPrivateChat(it)
                                selectedTab = SessionTab.CHAT
                            }, { participant ->
                                onPushToTalkChanged(false)
                                pokeRecipient =
                                    PokeRecipient(
                                        chat.binding,
                                        TextTarget.Private(participant.id, participant.uniqueIdentifier),
                                        participant.nickname.boundedText(256),
                                    )
                            })
                        }
                    SessionTab.CHAT ->
                        ChatConversationContent(
                            chat,
                            onChatDraft,
                            onChatSend,
                            onChatVisible,
                            { onPushToTalkChanged(false) },
                            onChatConversationSelected,
                            onChatConversationClosed,
                        )
                }
            }
        }
        if (selectedTab == SessionTab.CHAT) {
            CompactChatVoiceBar(state, onMicrophoneModeChanged, onPushToTalkChanged)
        } else {
            MicrophoneControl(
                mode = state.microphoneMode,
                isTransmitting = state.isTransmitting,
                isCapturing = state.isCapturing,
                captureDeferred = state.microphoneError == io.github.ts3mobile.app.service.UserMessage.MicrophoneResumeInApp,
                onMicrophoneModeChanged = onMicrophoneModeChanged,
                onPushToTalkChanged = onPushToTalkChanged,
            )
        }
    }
}
