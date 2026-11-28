package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.DisconnectOutcome
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExitRunnerTest {
    @Test
    fun anOldOperationHoldingTheSessionMutexCannotDelayTheExitCommand() =
        runTest {
            val host = Host(this)
            val ownership = SessionRequestOwnership()
            val session = Session()
            host.sessionMutex.lock()
            val job =
                launch {
                    SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                        ExitTrigger.TASK_REMOVED,
                        session,
                        false,
                        2_000L,
                        ownership.beginExit(),
                    ) {}
                }
            try {
                runCurrent()
                assertEquals(1, session.protocolCalls)
                assertTrue(session.closed)
                assertFalse(host.captureStopped)
            } finally {
                host.sessionMutex.unlock()
            }
            job.join()
            assertTrue(host.captureStopped && host.playbackStopped)
        }

    @Test
    fun audioCleanupFailureIsReportedSeparatelyFromServerConfirmation() =
        runTest {
            val host = Host(this).apply { failCaptureStop = true }
            val ownership = SessionRequestOwnership()
            SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                ExitTrigger.TASK_REMOVED,
                Session(),
                false,
                2_000L,
                ownership.beginExit(),
            ) {}

            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_CLEANUP_FAILED))
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
            assertFalse(host.events.contains(DiagnosticEventKind.EXIT_FAILED))
        }

    @Test
    fun slowAudioCleanupCannotSpendTheProtocolExitBudget() =
        runTest {
            val host = Host(this).apply { captureStopDelayMs = 1_500L }
            val ownership = SessionRequestOwnership()
            val session = Session()
            SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                ExitTrigger.TASK_REMOVED,
                session,
                false,
                2_000L,
                ownership.beginExit(),
            ) {}

            assertEquals(2_000L, session.protocolBudgetMs)
            assertTrue(session.closed)
            assertTrue(host.captureStopped && host.playbackStopped)
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
        }

    @Test
    fun audioCleanupTimeoutDoesNotEraseAnAlreadyConfirmedServerExit() =
        runTest {
            val host = Host(this).apply { captureStopDelayMs = 2_500L }
            val ownership = SessionRequestOwnership()
            val session = Session()
            SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                ExitTrigger.TASK_REMOVED,
                session,
                false,
                2_000L,
                ownership.beginExit(),
            ) {}

            assertEquals(1, session.protocolCalls)
            assertEquals(2_000L, session.protocolBudgetMs)
            assertTrue(session.closed)
            assertTrue(host.captureStopped && host.playbackStopped)
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_CLEANUP_OVERRUN))
            assertFalse(host.events.contains(DiagnosticEventKind.EXIT_TIMED_OUT))
        }

    @Test
    fun expiredBudgetStillReclaimsAudioAndSocketWithoutClaimingConfirmation() =
        runTest {
            val host = Host(this)
            val ownership = SessionRequestOwnership()
            val token = ownership.beginExit()
            val session = Session()
            var released = false
            SessionExitRunner(host, ownership, { 2_001L }, { false }).run(
                ExitTrigger.TASK_REMOVED,
                session,
                false,
                2_000L,
                token,
            ) { released = true }

            assertTrue(released)
            assertTrue(session.closed)
            assertEquals(0, session.protocolCalls)
            assertTrue(host.captureStopped && host.playbackStopped)
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_TIMED_OUT))
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_CLEANUP_OVERRUN))
            assertFalse(host.events.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
        }

    @Test
    fun replacedExitReleasesItsSocketAndKeepsTheNewRequestsState() =
        runTest {
            val host = Host(this)
            val ownership = SessionRequestOwnership()
            val token = ownership.beginExit()
            ownership.beginConnect()
            host.mutableState.value = TeamSpeakServiceState(serverLabel = "new request")
            val session = Session()
            SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                ExitTrigger.USER_DISCONNECT,
                session,
                false,
                2_000L,
                token,
            ) {}

            assertTrue(session.closed)
            assertEquals("new request", host.mutableState.value.serverLabel)
            assertTrue(
                host.events.indexOf(DiagnosticEventKind.EXIT_REQUEST_SENT) < host.events.indexOf(DiagnosticEventKind.EXIT_SERVER_CONFIRMED),
            )
        }

    @Test
    fun destructionOnlyReleasesCapturedSocketAndDoesNotQueueServiceTeardown() =
        runTest {
            val host = Host(this)
            val ownership = SessionRequestOwnership()
            val session = Session()
            SessionExitRunner(host, ownership, { testScheduler.currentTime }, { true }).run(
                ExitTrigger.TASK_REMOVED,
                session,
                true,
                2_000L,
                ownership.beginExit(),
            ) {}

            assertTrue(session.closed)
            assertEquals(0, session.protocolCalls)
            assertEquals(0, host.teardowns)
            assertFalse(host.events.contains(DiagnosticEventKind.EXIT_SERVER_CONFIRMED))
        }

    @Test
    fun exitResetPreservesMicrophonePreferencesAndAudioDiagnostics() =
        runTest {
            val host = Host(this)
            val savedSettings = io.github.ts3mobile.audio.opus.MicrophoneSettings(volumePercent = 180, noiseSuppression = false)
            val savedAudio = AudioDiagnosticStatus(savedSamples = 7, sessions = 2, marks = 1)
            val savedRuntime = BackgroundRuntimeState(keepCpuAwake = true)
            host.mutableState.value =
                TeamSpeakServiceState(
                    microphoneMode = MicrophoneMode.CONTINUOUS,
                    microphoneSettings = savedSettings,
                    audioDiagnostics = savedAudio,
                    backgroundRuntime = savedRuntime,
                    playbackMuted = true,
                    isTransmitting = true,
                )
            val ownership = SessionRequestOwnership()
            SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                ExitTrigger.USER_DISCONNECT,
                Session(),
                false,
                2_000L,
                ownership.beginExit(),
            ) {}

            val finalState = host.mutableState.value
            assertEquals(MicrophoneMode.CONTINUOUS, finalState.microphoneMode)
            assertEquals(savedSettings, finalState.microphoneSettings)
            assertEquals(savedAudio, finalState.audioDiagnostics)
            assertEquals(savedRuntime, finalState.backgroundRuntime)
            assertFalse(finalState.playbackMuted)
            assertFalse(finalState.isTransmitting)
        }

    @Test
    fun timeoutInsideStopAudioDoesNotRunDuplicatePlaybackStopAndRespectsSessionMutex() =
        runTest {
            val slowCaptureHost = Host(this).apply { captureStopDelayMs = 2_500L }
            val ownership = SessionRequestOwnership()
            SessionExitRunner(slowCaptureHost, ownership, { testScheduler.currentTime }, { false }).run(
                ExitTrigger.TASK_REMOVED,
                Session(),
                false,
                2_000L,
                ownership.beginExit(),
            ) {}
            assertEquals(1, slowCaptureHost.playbackStops)
            assertTrue(slowCaptureHost.captureStopped)

            val contendedHost = Host(this)
            val contendedOwnership = SessionRequestOwnership()
            contendedHost.sessionMutex.lock()
            var nowMs = 0L
            val exitJob =
                launch {
                    SessionExitRunner(contendedHost, contendedOwnership, { nowMs }, { false }).run(
                        ExitTrigger.TASK_REMOVED,
                        Session(),
                        false,
                        2_000L,
                        contendedOwnership.beginExit(),
                    ) {}
                }
            runCurrent()
            // Advance past the 2s exit budget while still within the 500ms cleanup mutex wait.
            nowMs = 2_100L
            advanceTimeBy(2_100L)
            runCurrent()
            assertFalse("stopAudio ran without holding sessionMutex", contendedHost.captureStopped)
            assertEquals(0, contendedHost.playbackStops)

            contendedHost.sessionMutex.unlock()
            exitJob.join()
            assertTrue(contendedHost.captureStopped && contendedHost.playbackStopped)
            assertEquals(1, contendedHost.playbackStops)
        }

    @Test
    fun stuckSessionMutexCompletesExitWithinBoundedGraceAndReclaimsDeferredOnlyIfNotReplaced() =
        runTest {
            val host = Host(this)
            val ownership = SessionRequestOwnership()
            host.sessionMutex.lock()
            val exitJob =
                launch {
                    SessionExitRunner(host, ownership, { testScheduler.currentTime }, { false }).run(
                        ExitTrigger.USER_DISCONNECT,
                        Session(),
                        false,
                        2_000L,
                        ownership.beginExit(),
                    ) {}
                }

            // Advance past both the 2s exit budget and the 500ms cleanup mutex wait without unlocking.
            advanceTimeBy(3_000L)
            runCurrent()
            assertTrue("exitJob hung indefinitely on stuck sessionMutex", exitJob.isCompleted)
            assertEquals(ConnectionPhase.DISCONNECTED, host.mutableState.value.status.phase)
            assertTrue(host.immediateMicrophoneStops >= 1)
            assertEquals(1, host.teardowns)
            assertFalse(host.captureStopped)
            assertEquals(0, host.playbackStops)
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_CLEANUP_OVERRUN))
            assertTrue(host.events.contains(DiagnosticEventKind.EXIT_CLEANUP_FAILED))

            // Once the stuck operation releases sessionMutex (with no replacement session), deferred cleanup runs.
            host.sessionMutex.unlock()
            runCurrent()
            assertTrue(host.captureStopped && host.playbackStopped)
            assertEquals(1, host.playbackStops)

            // If a replacement session takes ownership before the stuck mutex unlocks, deferred cleanup stands down.
            val replacedHost = Host(this)
            val replacedOwnership = SessionRequestOwnership()
            replacedHost.sessionMutex.lock()
            val replacedExitJob =
                launch {
                    SessionExitRunner(replacedHost, replacedOwnership, { testScheduler.currentTime }, { false }).run(
                        ExitTrigger.USER_DISCONNECT,
                        Session(),
                        false,
                        2_000L,
                        replacedOwnership.beginExit(),
                    ) {}
                }
            advanceTimeBy(3_000L)
            runCurrent()
            assertTrue(replacedExitJob.isCompleted)
            replacedOwnership.beginConnect()
            replacedHost.sessionMutex.unlock()
            runCurrent()
            assertFalse("deferred cleanup touched audio belonging to replacement session", replacedHost.captureStopped)
            assertEquals(0, replacedHost.playbackStops)
        }

    @Test
    fun tryLockBoundedReleasesIfCancelledOnGrantAndTimesOutCleanly() =
        runTest {
            val mutex = Mutex()
            mutex.lock()
            assertFalse(mutex.tryLockBounded(100L))
            assertTrue(mutex.isLocked)

            val waiterJob =
                launch {
                    assertTrue(mutex.tryLockBounded(500L))
                    mutex.unlock()
                }
            runCurrent()
            waiterJob.cancel()
            mutex.unlock()
            runCurrent()
            assertFalse("cancelled bounded waiter leaked mutex", mutex.isLocked)

            assertTrue(mutex.tryLockBounded(100L))
            mutex.unlock()
        }

    private class Session : Ts3SessionClient {
        var closed = false
        var protocolCalls = 0
        var protocolBudgetMs = 0L

        override fun connect(
            config: ServerConfig,
            identityMaterial: String,
            listener: Ts3SessionListener,
        ) = Unit

        override fun setVoiceSource(source: EncodedVoiceSource?) = Unit

        override fun joinChannel(
            channelId: Int,
            password: String,
        ) = Unit

        override fun disconnect(reason: String) = Unit

        override suspend fun disconnectBounded(
            timeoutMs: Long,
            reason: String,
            onRequestSent: () -> Unit,
        ): DisconnectOutcome {
            protocolCalls++
            protocolBudgetMs = timeoutMs
            onRequestSent()
            return DisconnectOutcome.SERVER_CONFIRMED
        }

        override fun close() {
            closed = true
        }
    }

    private class Host(override val serviceScope: CoroutineScope) : ConnectionCoordinatorHost {
        override val sessionMutex = Mutex()
        override val sessionGeneration = SessionGeneration()
        override val networkAvailable = MutableStateFlow(true)
        override val mutableState = MutableStateFlow(TeamSpeakServiceState())
        override val reconnectPolicy = ReconnectPolicy()
        override val diagnosticsRecorder = DiagnosticsRecorder()
        val events = mutableListOf<DiagnosticEventKind>()
        var captureStopped = false
        var playbackStopped = false
        var playbackStops = 0
        var immediateMicrophoneStops = 0
        var teardowns = 0
        var captureStopDelayMs = 0L
        var failCaptureStop = false

        override fun conciseMessage(error: Throwable) = "failure"

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() {
            check(!failCaptureStop) { "fixture cleanup failure" }
            delay(captureStopDelayMs)
            captureStopped = true
        }

        override suspend fun createIdentity() = "fixture"

        override fun startAudioRouting() = Unit

        override fun stopAudioRouting() = Unit

        override fun applySelectedPlaybackMuted() = Unit

        override suspend fun startPlayback() = Unit

        override fun stopPlayback() {
            playbackStops++
            playbackStopped = true
        }

        override fun clearPlaybackMuted() = Unit

        override fun attachMicrophoneTo(session: Ts3SessionClient) = Unit

        override fun stopMicrophoneImmediately() {
            immediateMicrophoneStops++
        }

        override fun reconcileMicrophone() = Unit

        override fun applyParticipantAudioSettings(snapshot: SessionSnapshot) = Unit

        override fun submitVoiceFrame(frame: VoiceFrame) = Unit

        override fun removeForegroundNotification() = Unit

        override fun requestStopSelf() = Unit

        override fun recordDiagnostic(
            kind: DiagnosticEventKind,
            code: Long,
        ) {
            events += kind
        }

        override fun finishExitOnMainThread(exitToken: Long) {
            teardowns++
        }
    }
}
