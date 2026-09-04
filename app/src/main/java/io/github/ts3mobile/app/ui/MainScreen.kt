package io.github.ts3mobile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ConnectionPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    form: ConnectionFormState,
    serviceState: TeamSpeakServiceState,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "TS3 Mobile",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    StatusIndicator(serviceState.status.phase)
                    Spacer(Modifier.width(16.dp))
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
        ) {
            serviceState.status.detail?.let { detail ->
                StatusMessage(serviceState.status.phase, detail)
            }
            serviceState.microphoneError?.let { detail ->
                StatusMessage(ConnectionPhase.ERROR, detail)
            }
            serviceState.channelError?.let { detail ->
                StatusMessage(ConnectionPhase.ERROR, detail)
            }
            serviceState.audioRouting.error?.let { detail ->
                StatusMessage(ConnectionPhase.ERROR, detail)
            }

            if (serviceState.status.phase == ConnectionPhase.CONNECTED) {
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
                    onCopyDiagnostics = onCopyDiagnostics,
                )
            } else {
                ConnectionForm(
                    form = form,
                    phase = serviceState.status.phase,
                    onHostChanged = onHostChanged,
                    onPortChanged = onPortChanged,
                    onNicknameChanged = onNicknameChanged,
                    onPasswordChanged = onPasswordChanged,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(phase: ConnectionPhase) {
    val (label, color) =
        when (phase) {
            ConnectionPhase.DISCONNECTED -> "未连接" to MaterialTheme.colorScheme.outline
            ConnectionPhase.CONNECTING -> "连接中" to MaterialTheme.colorScheme.tertiary
            ConnectionPhase.RECONNECTING -> "重连中" to MaterialTheme.colorScheme.tertiary
            ConnectionPhase.CONNECTED -> "已连接" to MaterialTheme.colorScheme.primary
            ConnectionPhase.DISCONNECTING -> "断开中" to MaterialTheme.colorScheme.tertiary
            ConnectionPhase.ERROR -> "连接失败" to MaterialTheme.colorScheme.error
        }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusMessage(
    phase: ConnectionPhase,
    detail: String,
) {
    val background =
        if (phase == ConnectionPhase.ERROR) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    val foreground =
        if (phase == ConnectionPhase.ERROR) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Text(
        text = detail,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        color = foreground,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
