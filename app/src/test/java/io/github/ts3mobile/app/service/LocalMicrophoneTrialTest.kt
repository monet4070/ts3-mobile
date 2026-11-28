package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun inlineDispatcherWithImmediateReleaseCheckStartsAndStopsWithoutCrashing() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = MutableStateFlow(connected())
        val capture = TrialSource()
        val trial = LocalMicrophoneTrial(scope, session, MicrophoneTrial(scope) { capture }, { true }) { true }
        try {
            trial.setVisible(true)
            trial.start(MicrophoneSettings())
            assertEquals(1, capture.starts)
            assertEquals(MicrophoneTrialPhase.RUNNING, trial.state.value.phase)
            assertTrue(trial.stop())
            assertEquals(1, capture.stops)
            assertEquals(MicrophoneTrialPhase.IDLE, trial.state.value.phase)
            assertTrue(trial.isInputReleased)
        } finally {
            scope.cancel()
        }
    }

    @Test fun cancelDuringStartHandoffNeverLeavesCaptureRunning() {
        runBlocking {
            val inlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val session = MutableStateFlow(connected())
            val inlineCapture = TrialSource()
            lateinit var inlineTrial: LocalMicrophoneTrial
            inlineTrial =
                LocalMicrophoneTrial(
                    inlineScope,
                    session,
                    MicrophoneTrial(inlineScope) { inlineCapture },
                    {
                        inlineTrial.stop()
                        true
                    },
                ) { true }
            try {
                inlineTrial.setVisible(true)
                inlineTrial.start(MicrophoneSettings())
                assertEquals(0, inlineCapture.starts)
                assertEquals(MicrophoneTrialPhase.IDLE, inlineTrial.state.value.phase)
                assertTrue(inlineTrial.isInputReleased)
            } finally {
                inlineScope.cancel()
            }

            repeat(20) {
                val raceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val gate = CompletableDeferred<Unit>()
                val raceCapture = TrialSource()
                val raceTrial =
                    LocalMicrophoneTrial(
                        raceScope,
                        session,
                        MicrophoneTrial(raceScope, Dispatchers.IO) { raceCapture },
                        {
                            gate.await()
                            true
                        },
                    ) { true }
                try {
                    raceTrial.setVisible(true)
                    raceTrial.start(MicrophoneSettings())
                    val stopDeferred = async(Dispatchers.Default) { raceTrial.stopAndAwait() }
                    gate.complete(Unit)
                    assertTrue(withTimeout(3_000) { stopDeferred.await() })
                    assertTrue(raceTrial.isInputReleased)
                    assertEquals(MicrophoneTrialPhase.IDLE, raceTrial.state.value.phase)
                    assertEquals(raceCapture.starts, raceCapture.stops)
                } finally {
                    raceScope.cancel()
                }
            }
        }
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
