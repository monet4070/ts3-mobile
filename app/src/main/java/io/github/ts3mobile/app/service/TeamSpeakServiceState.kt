package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot

data class TeamSpeakServiceState(
    val status: ConnectionStatus = ConnectionStatus(),
    val snapshot: SessionSnapshot = SessionSnapshot.Empty,
    val serverLabel: String? = null,
    val identityReady: Boolean = false,
    val playbackMuted: Boolean = false,
    val participantAudioSettings: Map<String, ParticipantAudioSettings> = emptyMap(),
    val microphoneMode: MicrophoneMode = MicrophoneMode.PUSH_TO_TALK,
    val isTransmitting: Boolean = false,
    val microphoneError: String? = null,
    val switchingChannelId: Int? = null,
    val channelError: String? = null,
    val audioRouting: AudioRoutingState = AudioRoutingState.Default,
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot.Empty,
)
