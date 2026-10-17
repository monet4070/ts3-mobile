package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.UserMessage
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
    val largeText = LocalDensity.current.fontScale > 1.3f
    Scaffold(
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconTile(UiIcon.Audio, Modifier.size(32.dp))
                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = if (largeText) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!largeText) {
                                Text(
                                    stringResource(R.string.app_tagline),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
            val context = LocalContext.current
            val statusText =
                serviceState.statusMessage?.resolve(context) ?: serviceState.status.detail
            statusText?.let { detail ->
                StatusMessage(serviceState.status.phase, detail)
            }
            serviceState.microphoneError?.let { message ->
                StatusMessage(ConnectionPhase.ERROR, message.resolve(context))
            }
            serviceState.channelError?.let { message ->
                StatusMessage(ConnectionPhase.ERROR, message.resolve(context))
            }
            serviceState.audioRouting.error?.let { error ->
                StatusMessage(ConnectionPhase.ERROR, UserMessage.AudioRouting(error).resolve(context))
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
