package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMicrophoneTrialTest {
    @Test fun onlineTrialRequiresVisibilityPermissionAndReleasedVoiceInput() =
        runTest {
            val session = MutableStateFlow(connected())
            var released = false
            var permission = true
            val capture = TrialSource()
            val trial =
                LocalMicrophoneTrial(
                    backgroundScope,
                    session,
                    MicrophoneTrial(backgroundScope) { capture },
                    { released },
                ) { permission }
            trial.watchSession()
            trial.start(MicrophoneSettings())
            trial.setVisible(true)
            session.value = connected().copy(microphoneMode = MicrophoneMode.VOLUME_GATE)
            trial.start(MicrophoneSettings())
            session.value = connected().copy(status = ConnectionStatus(ConnectionPhase.RECONNECTING))
            trial.start(MicrophoneSettings())
            session.value = connected()
            permission = false
            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(0, capture.starts)

            permission = true
            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(MicrophoneTrialPhase.UNAVAILABLE, trial.state.value.phase)
            assertEquals(0, capture.starts)

            released = true
            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(1, capture.starts)
            trial.inputChanged()
            assertEquals(1, capture.stops)
            assertEquals(MicrophoneMode.OFF, session.value.microphoneMode)
        }

    @Test fun aNewSessionCancelsPendingStartAndRouteChangeReleasesCapture() =
        runTest {
            val session = MutableStateFlow(connected())
            val released = CompletableDeferred<Boolean>()
            val capture = TrialSource()
            val trial =
                LocalMicrophoneTrial(
                    backgroundScope,
                    session,
                    MicrophoneTrial(backgroundScope) { capture },
                    { released.await() },
                ) { true }
            trial.watchSession()
            trial.setVisible(true)
            trial.start(MicrophoneSettings())
            runCurrent()
            session.value = connected("replacement")
            released.complete(true)
            runCurrent()
            assertEquals(0, capture.starts)

            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(1, capture.starts)
            session.value = session.value.copy(audioRouting = session.value.audioRouting.copy(switching = true))
            runCurrent()
            assertEquals(1, capture.stops)
            assertEquals(MicrophoneTrialPhase.IDLE, trial.state.value.phase)
        }

    @Test fun disconnectedTrialLeavesVoiceModeUntouchedAndStopsOnConnectionChange() =
        runTest {
            val session = MutableStateFlow(TeamSpeakServiceState())
            val capture = TrialSource()
            val trial = LocalMicrophoneTrial(backgroundScope, session, MicrophoneTrial(backgroundScope) { capture }, { true }) { true }
            trial.watchSession()
            trial.setVisible(true)
            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(1, capture.starts)
            assertEquals(MicrophoneMode.PUSH_TO_TALK, session.value.microphoneMode)
            session.value = session.value.copy(status = ConnectionStatus(ConnectionPhase.CONNECTING))
            runCurrent()
            assertEquals(1, capture.stops)
            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(1, capture.starts)
            session.value = session.value.copy(status = ConnectionStatus(ConnectionPhase.ERROR))
            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(2, capture.starts)
            trial.setVisible(false)
            assertEquals(2, capture.stops)
        }

    private fun connected(token: String = "fixture") =
        TeamSpeakServiceState(
            status = ConnectionStatus(ConnectionPhase.CONNECTED),
            microphoneMode = MicrophoneMode.OFF,
            bookmarkConnection = BookmarkConnection(token, "fixture.example.invalid", 9987, "Fixture"),
        )

    private class TrialSource : MicrophoneTrialSource {
        var starts = 0
        var stops = 0
        override val isCapturing get() = starts > stops
        override val isSilenced = false

        override fun start(settings: MicrophoneSettings) {
            starts++
        }

        override fun reading() = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING)

        override fun close() {
            stops++
        }
    }
}
