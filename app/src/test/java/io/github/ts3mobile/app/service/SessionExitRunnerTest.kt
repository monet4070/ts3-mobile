package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.DisconnectOutcome
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import io.github.ts3mobile.protocol.VoiceFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionExitRunnerTest {
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

    private class Session : Ts3SessionClient {
        var closed = false
        var protocolCalls = 0

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
        var teardowns = 0

        override fun conciseMessage(error: Throwable) = "failure"

        override fun updateNotification() = Unit

        override fun refreshDiagnostics() = Unit

        override fun resetParticipantGains() = Unit

        override fun resetMicrophoneForConnection() = Unit

        override suspend fun stopMicrophoneCapture() {
            captureStopped = true
        }

        override suspend fun createIdentity() = "fixture"

        override fun startAudioRouting() = Unit

        override fun stopAudioRouting() = Unit

        override fun applySelectedPlaybackMuted() = Unit

        override fun startPlayback() = Unit

        override fun stopPlayback() {
            playbackStopped = true
        }

        override fun clearPlaybackMuted() = Unit

        override fun attachMicrophoneTo(session: Ts3SessionClient) = Unit

        override fun stopMicrophoneImmediately() = Unit

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
