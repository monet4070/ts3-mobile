package io.github.ts3mobile.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.settings.MicrophonePreferenceField
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.flow.Flow

private enum class SettingsDestination { OVERVIEW, MICROPHONE, PLAYBACK, BACKGROUND, DIAGNOSTICS }

@Composable
internal fun AppSettings(
    state: TeamSpeakServiceState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onMicrophonePreferenceEdit: (MicrophonePreferenceField, MicrophoneSettings) -> Unit,
    microphonePreferenceStatus: MicrophonePreferenceStatus,
    onMicrophonePreferenceCommit: () -> Unit,
    onMicrophonePreferenceRetry: () -> Unit,
    onMicrophonePreferenceReset: () -> Unit,
    microphoneLevels: Flow<MicrophoneLevel?>,
    onPushToTalkChanged: (Boolean) -> Unit,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
    microphoneTrialDialog: (@Composable (onDismiss: () -> Unit) -> Unit)?,
    onNotificationSettings: () -> Unit,
    onAppearance: () -> Unit,
    onIdentityManagement: () -> Unit,
    onRefreshBackgroundRuntime: () -> Unit,
    onKeepCpuAwakeChanged: (Boolean) -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onSaveDiagnostics: () -> Unit,
    onMarkAudioIssue: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenRepository: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.OVERVIEW) }
    val pages = rememberSaveableStateHolder()
    val backToOverview = { destination = SettingsDestination.OVERVIEW }
    when (destination) {
        SettingsDestination.MICROPHONE ->
            MicrophoneSettingsScreen(
                state = state,
                snackbar = snackbar,
                onChange = onMicrophonePreferenceEdit,
                onBack = backToOverview,
                preferenceStatus = microphonePreferenceStatus,
                onCommit = onMicrophonePreferenceCommit,
                onRetry = onMicrophonePreferenceRetry,
                onReset = onMicrophonePreferenceReset,
                levels = microphoneLevels,
                onPushToTalkChanged = onPushToTalkChanged,
                onMicrophoneModeChanged = onMicrophoneModeChanged,
                microphoneTrialDialog = microphoneTrialDialog,
            )
        SettingsDestination.PLAYBACK -> {
            val preferences = (LocalContext.current.applicationContext as Ts3MobileApplication).playbackPreferences
            val playbackPreference by preferences.state.collectAsStateWithLifecycle()
            PlaybackSettingsScreen(playbackPreference, preferences::select, preferences::reload, backToOverview)
        }
        SettingsDestination.OVERVIEW ->
            pages.SaveableStateProvider(SettingsDestination.OVERVIEW.name) {
                SettingsPage(R.string.settings_title, onBack, snackbar) {
                    SettingsActionGroup(
                        listOf(
                            SettingsAction(R.string.microphone_settings, UiIcon.Mic) {
                                destination = SettingsDestination.MICROPHONE
                            },
                            SettingsAction(R.string.playback_settings_title, UiIcon.Headphones) {
                                destination = SettingsDestination.PLAYBACK
                            },
                        ),
                        R.string.settings_voice,
                    )
                    SettingsActionGroup(
                        listOf(
                            SettingsAction(
                                R.string.private_message_notification_settings,
                                UiIcon.Chat,
                                onClick = onNotificationSettings,
                            ),
                        ),
                        R.string.settings_notifications,
                    )
                    SettingsActionGroup(
                        listOf(
                            SettingsAction(R.string.appearance_title, UiIcon.Sliders, onClick = onAppearance),
                            SettingsAction(R.string.identity_title, UiIcon.Shield, onClick = onIdentityManagement),
                        ),
                        R.string.settings_personalization,
                    )
                    SettingsActionGroup(
                        listOf(
                            SettingsAction(R.string.background_runtime_title, UiIcon.Battery) {
                                onRefreshBackgroundRuntime()
                                destination = SettingsDestination.BACKGROUND
                            },
                            SettingsAction(R.string.tab_diagnostics, UiIcon.Activity) {
                                destination = SettingsDestination.DIAGNOSTICS
                            },
                            SettingsAction(R.string.updates_title, UiIcon.Info, onClick = onCheckUpdates),
                            SettingsAction(R.string.support_star, UiIcon.Star, onClick = onOpenRepository),
                        ),
                        R.string.settings_help,
                    )
                }
            }
        SettingsDestination.BACKGROUND ->
            BackgroundRuntimeScreen(
                state = state.backgroundRuntime,
                snackbar = snackbar,
                onKeepCpuAwakeChanged = onKeepCpuAwakeChanged,
                onBatterySettings = onBatterySettings,
                onAppSettings = onAppSettings,
                onCopyDiagnostics = onCopyDiagnostics,
                onBack = backToOverview,
            )

        SettingsDestination.DIAGNOSTICS ->
            SettingsPage(R.string.tab_diagnostics, backToOverview, snackbar) {
                DiagnosticsPanel(state.diagnostics, onCopyDiagnostics, state.audioDiagnostics, onSaveDiagnostics, onMarkAudioIssue)
            }
    }
}
