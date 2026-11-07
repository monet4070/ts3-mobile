package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.audio.opus.EchoCancellationState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot

data class TeamSpeakServiceState(
    val status: ConnectionStatus = ConnectionStatus(),
    val snapshot: SessionSnapshot = SessionSnapshot.Empty,
    val serverLabel: String? = null,
    val identityReady: Boolean = false,
    val playbackMuted: Boolean = false,
    val playbackStatus: PlaybackStatus = PlaybackStatus.STOPPED,
    val participantAudioSettings: Map<String, ParticipantAudioSettings> = emptyMap(),
    val microphoneMode: MicrophoneMode = MicrophoneMode.PUSH_TO_TALK,
    val presence: PresenceSyncState = PresenceSyncState(),
    val isTransmitting: Boolean = false,
    val isCapturing: Boolean = false,
    val microphoneSettings: MicrophoneSettings = MicrophoneSettings(),
    val echoCancellationState: EchoCancellationState = EchoCancellationState.PENDING,
    val microphoneError: UserMessage? = null,
    val switchingChannelId: Int? = null,
    val channelError: UserMessage? = null,
    val audioRouting: AudioRoutingState = AudioRoutingState.Default,
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot.Empty,
    val backgroundRuntime: BackgroundRuntimeState = BackgroundRuntimeState(),
    /**
     * App-generated text for the current [status]. Takes precedence over
     * [ConnectionStatus.detail], which stays the protocol's technical detail.
     */
    val statusMessage: UserMessage? = null,
    val bookmarkConnection: BookmarkConnection? = null,
)

val TeamSpeakServiceState.hasPlaybackFailure: Boolean
    get() = status.phase == ConnectionPhase.CONNECTED && playbackStatus == PlaybackStatus.FAILED

/**
 * Replaces the connection status together with its user-facing message so a
 * message from an earlier phase can never outlive the status that produced it.
 */
fun TeamSpeakServiceState.withStatus(
    status: ConnectionStatus,
    message: UserMessage? = null,
): TeamSpeakServiceState = copy(status = status, statusMessage = message)
