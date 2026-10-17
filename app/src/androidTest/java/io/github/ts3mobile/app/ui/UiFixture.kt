package io.github.ts3mobile.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.service.ParticipantAudioSettings
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3Participant
import java.util.Locale

internal class UiFixture(connected: Boolean = false) {
    val form = mutableStateOf(ConnectionFormState(host = "voice.example.com", nickname = "Alex"))
    val state = mutableStateOf(if (connected) connectedState() else TeamSpeakServiceState())
    var connects = 0
    var disconnects = 0
    var copies = 0
    val joins = mutableListOf<Pair<Int, String>>()
    val transmission = mutableListOf<Boolean>()

    @Composable
    fun Render(
        dark: Boolean = false,
        locale: String = "en",
        fontScale: Float = 1f,
    ) {
        val base = LocalContext.current
        val config = remember(locale) { Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) } }
        val context = remember(config) { base.createConfigurationContext(config) }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides config,
            LocalDensity provides Density(LocalDensity.current.density, fontScale),
        ) {
            Ts3MobileTheme(darkTheme = dark) {
                MainScreen(
                    form = form.value, serviceState = state.value,
                    onHostChanged = { form.value = form.value.copy(host = it) },
                    onPortChanged = { form.value = form.value.copy(port = it) },
                    onNicknameChanged = { form.value = form.value.copy(nickname = it) },
                    onPasswordChanged = { form.value = form.value.copy(password = it) },
                    onConnect = {
                        connects++
                        form.value = form.value.copy(submitted = true)
                    },
                    onDisconnect = { disconnects++ },
                    onPlaybackMutedChange = { state.value = state.value.copy(playbackMuted = it) },
                    onParticipantMutedChange = { key, muted ->
                        val settings = state.value.participantAudioSettings[key] ?: ParticipantAudioSettings()
                        state.value =
                            state.value.copy(
                                participantAudioSettings = state.value.participantAudioSettings + (key to settings.copy(muted = muted)),
                            )
                    },
                    onParticipantVolumeChange = { key, volume ->
                        val settings = state.value.participantAudioSettings[key] ?: ParticipantAudioSettings()
                        state.value =
                            state.value.copy(
                                participantAudioSettings =
                                    state.value.participantAudioSettings +
                                        (key to settings.copy(volumePercent = volume)),
                            )
                    },
                    onAudioRouteSelected = {},
                    onMicrophoneModeChanged = { state.value = state.value.copy(microphoneMode = it) },
                    onPushToTalkChanged = { transmission += it },
                    onJoinChannel = { id, password -> joins += id to password },
                    onCopyDiagnostics = { copies++ },
                )
            }
        }
    }
}

private fun connectedState() =
    TeamSpeakServiceState(
        status = ConnectionStatus(ConnectionPhase.CONNECTED),
        serverLabel = "The Common Room",
        identityReady = true,
        snapshot =
            SessionSnapshot(
                channels =
                    listOf(
                        Ts3Channel(1, 0, 0, "Lobby", 3, false, true),
                        Ts3Channel(2, 0, 1, "Design studio", 1, false, false),
                        Ts3Channel(3, 0, 2, "Private room", 0, true, false),
                        Ts3Channel(4, 2, 0, "After hours", 0, false, false),
                    ),
                participants =
                    listOf(
                        Ts3Participant(1, 1, "Alex", false, false, false),
                        Ts3Participant(2, 1, "Sam", true, false, false),
                        Ts3Participant(3, 1, "Jamie", false, true, false),
                        Ts3Participant(4, 2, "Morgan", false, false, false),
                    ),
                ownClientId = 1,
            ),
    )
