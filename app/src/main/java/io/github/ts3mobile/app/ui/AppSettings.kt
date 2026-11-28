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

internal enum class SettingsDestination {
    OVERVIEW,
    AUDIO_DEVICES,
    MICROPHONE,
    PLAYBACK,
    BACKGROUND,
    DIAGNOSTICS,
    CHAT_HISTORY,
    FEATURE_HELP,
}

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
    onHelpTopicRequested: (Int) -> Unit,
    guideRoute: GuideRoute? = null,
    onAudioInputSelected: (Int) -> Unit = {},
    onAudioOutputSelected: (Int) -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.OVERVIEW) }
    var guideDismissed by rememberSaveable(guideRoute) { mutableStateOf(false) }
    var settingsHelpRoute by rememberSaveable { mutableStateOf<GuideRoute?>(null) }
    val activeGuide = (settingsHelpRoute ?: guideRoute).takeUnless { guideDismissed }
    val pages = rememberSaveableStateHolder()
    val dismissGuide = { guideDismissed = true }
    val backToOverview = {
        dismissGuide()
        destination = SettingsDestination.OVERVIEW
    }
    GuideDestinationTip(activeGuide.takeIf { it?.settings == destination }) {
        when (destination) {
            SettingsDestination.AUDIO_DEVICES ->
                AudioDeviceSettingsScreen(state, onAudioInputSelected, onAudioOutputSelected, backToOverview)
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
                PlaybackSettingsScreen(playbackPreference, preferences::select, preferences::reload, backToOverview, state)
            }
            SettingsDestination.CHAT_HISTORY -> {
                val app = LocalContext.current.applicationContext as Ts3MobileApplication
                val identityVersion by app.identities.readVersion.collectAsStateWithLifecycle()
                ChatHistorySettingsScreen(app.chatHistory, identityVersion, app.identities::snapshot, backToOverview)
            }
            SettingsDestination.OVERVIEW ->
                pages.SaveableStateProvider(SettingsDestination.OVERVIEW.name) {
                    val context = LocalContext.current
                    GuideSettingsEntry(activeGuide, dismissGuide) {
                        SettingsPage(R.string.settings_title, onBack, snackbar) {
                            SettingsActionGroup(
                                listOf(
                                    SettingsAction(R.string.audio_device_settings_title, UiIcon.Audio) {
                                        destination = SettingsDestination.AUDIO_DEVICES
                                    },
                                    SettingsAction(
                                        R.string.microphone_settings,
                                        UiIcon.Mic,
                                        detail = state.microphoneMode.settingsLabel,
                                        guideTarget = GuideAnchor.SETTINGS_MICROPHONE,
                                    ) {
                                        destination = SettingsDestination.MICROPHONE
                                    },
                                    SettingsAction(
                                        R.string.playback_settings_title,
                                        UiIcon.Headphones,
                                        guideTarget = GuideAnchor.SETTINGS_PLAYBACK,
                                    ) {
                                        destination = SettingsDestination.PLAYBACK
                                    },
                                    SettingsAction(
                                        R.string.settings_floating_ptt,
                                        UiIcon.FloatingPtt,
                                        detail = R.string.settings_floating_ptt_summary,
                                        guideTarget = GuideAnchor.SETTINGS_FLOATING,
                                    ) {
                                        context.startActivity(context.guidedFloatingPttIntent(activeGuide))
                                        dismissGuide()
                                    },
                                ),
                                R.string.settings_voice,
                            )
                            SettingsActionGroup(
                                listOf(
                                    SettingsAction(
                                        R.string.private_message_notification_settings,
                                        UiIcon.Chat,
                                        guideTarget = GuideAnchor.NOTIFICATION_SETTINGS,
                                    ) {
                                        dismissGuide()
                                        onNotificationSettings()
                                    },
                                ),
                                R.string.settings_notifications,
                            )
                            SettingsActionGroup(
                                listOf(
                                    SettingsAction(R.string.appearance_title, UiIcon.Sliders, onClick = onAppearance),
                                    SettingsAction(R.string.identity_title, UiIcon.Shield, guideTarget = GuideAnchor.SETTINGS_IDENTITY) {
                                        if (activeGuide == GuideRoute.IDENTITY) {
                                            context.startActivity(context.guidedIdentityIntent())
                                            dismissGuide()
                                        } else {
                                            onIdentityManagement()
                                        }
                                    },
                                    SettingsAction(
                                        R.string.chat_history_title,
                                        UiIcon.History,
                                        guideTarget = GuideAnchor.SETTINGS_HISTORY,
                                    ) {
                                        destination = SettingsDestination.CHAT_HISTORY
                                    },
                                ),
                                R.string.settings_personalization,
                            )
                            SettingsActionGroup(
                                listOf(
                                    SettingsAction(R.string.background_runtime_title, UiIcon.Battery) {
                                        onRefreshBackgroundRuntime()
                                        destination = SettingsDestination.BACKGROUND
                                    },
                                    SettingsAction(
                                        R.string.tab_diagnostics,
                                        UiIcon.Activity,
                                        guideTarget = GuideAnchor.SETTINGS_DIAGNOSTICS,
                                    ) {
                                        destination = SettingsDestination.DIAGNOSTICS
                                    },
                                    SettingsAction(R.string.guide_help, UiIcon.Search) {
                                        destination = SettingsDestination.FEATURE_HELP
                                    },
                                    SettingsAction(R.string.updates_title, UiIcon.Info, onClick = onCheckUpdates),
                                    SettingsAction(R.string.support_star, UiIcon.Star, onClick = onOpenRepository),
                                ),
                                R.string.settings_help,
                            )
                        }
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

            SettingsDestination.FEATURE_HELP ->
                SettingsPage(R.string.guide_help, backToOverview, snackbar) {
                    SettingsActionGroup(
                        guideTopics.map { topic ->
                            SettingsAction(topic.title, topic.icon) {
                                if (topic.route?.settingsEntry != null) {
                                    settingsHelpRoute = topic.route
                                    guideDismissed = false
                                    destination = SettingsDestination.OVERVIEW
                                } else {
                                    onHelpTopicRequested(topic.title)
                                }
                            }
                        },
                    )
                }
        }
    }
}

private val MicrophoneMode.settingsLabel: Int
    get() =
        when (this) {
            MicrophoneMode.OFF -> R.string.microphone_mode_off
            MicrophoneMode.PUSH_TO_TALK -> R.string.microphone_mode_push_to_talk
            MicrophoneMode.CONTINUOUS -> R.string.microphone_mode_continuous
            MicrophoneMode.VOLUME_GATE -> R.string.microphone_mode_gate
        }
