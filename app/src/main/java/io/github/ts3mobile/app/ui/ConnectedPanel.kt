package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState

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
    onCopyDiagnostics: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SessionHeader(state, onDisconnect, onPlaybackMutedChange, onAudioRouteSelected)
        NavigationTabs(selectedTab, { selectedTab = it })
        if (selectedTab == 0) {
            SectionLabel(stringResource(R.string.channel_gesture_hint), Modifier.padding(horizontal = 20.dp))
        }
        Panel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.medium)) {
                when (selectedTab) {
                    0 -> ChannelList(state, onJoinChannel)
                    1 -> ParticipantList(state, onParticipantMutedChange, onParticipantVolumeChange)
                    else -> DiagnosticsPanel(state.diagnostics, onCopyDiagnostics)
                }
            }
        }
        MicrophoneControl(state.microphoneMode, state.isTransmitting, onMicrophoneModeChanged, onPushToTalkChanged)
    }
}
