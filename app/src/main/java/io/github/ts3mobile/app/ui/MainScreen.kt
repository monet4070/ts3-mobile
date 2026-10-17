package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.service.UserMessage
import io.github.ts3mobile.protocol.ConnectionPhase

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
) {
    var showBackgroundRuntime by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
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
                    Box {
                        IconButton(onClick = {
                            onPushToTalkChanged(false)
                            showMore = true
                        }) {
                            AppIcon(UiIcon.Sliders, stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.background_runtime_title)) },
                                onClick = {
                                    showMore = false
                                    onRefreshBackgroundRuntime()
                                    showBackgroundRuntime = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_diagnostics)) },
                                onClick = {
                                    showMore = false
                                    showDiagnostics = true
                                },
                            )
                        }
                    }
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
            val statusText = serviceState.resolveStatusText(context)
            if (serviceState.status.phase == ConnectionPhase.CONNECTED) {
                statusText?.let { detail -> StatusMessage(serviceState.status.phase, detail) }
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
                )
            }
        }
    }
}
