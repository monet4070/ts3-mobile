package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.ChannelDetails
import io.github.ts3mobile.protocol.ChannelInfoResult
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.ControlOutcome
import io.github.ts3mobile.protocol.ControlStatus
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.OwnPresence
import io.github.ts3mobile.protocol.ServerConfig
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Channel
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.Ts3SessionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControlsControllerTest {
    private fun state() =
        MutableStateFlow(
            TeamSpeakServiceState(
                status = ConnectionStatus(ConnectionPhase.CONNECTED),
                snapshot = SessionSnapshot(channels = listOf(Ts3Channel(1, 0, 0, "Fixture", 0, false, true))),
            ),
        )

    @Test fun ownMuteFlagsTrackExplicitIntentAndAvailabilityInsteadOfFrameActivity() {
        val state = TeamSpeakServiceState(microphoneMode = MicrophoneMode.VOLUME_GATE, isCapturing = true)
        assertFalse(desiredPresence(state, true).inputMuted)
        assertFalse(desiredPresence(state.copy(isTransmitting = true), true).inputMuted)
        assertFalse(desiredPresence(state.copy(microphoneMode = MicrophoneMode.PUSH_TO_TALK, isCapturing = false), true).inputMuted)
        assertTrue(desiredPresence(state.copy(microphoneMode = MicrophoneMode.OFF), true).inputMuted)
        assertTrue(desiredPresence(state, false).inputMuted)
        assertTrue(desiredPresence(state.copy(microphoneError = UserMessage.MicrophoneResumeInApp), true).inputMuted)
        assertTrue(desiredPresence(state.copy(playbackMuted = true), true).outputMuted)
    }

    @Test fun coalescesFrameChangesAndSynchronizesAwayMuteAndReconnect() =
        runTest {
            val state = state()
            val controller = SessionControlsController(backgroundScope, state)
            val first = FakeSession()
            controller.attach(first)
            runCurrent()
            assertEquals(1, first.presences.size)
            state.value = state.value.copy(isCapturing = true, isTransmitting = true)
            runCurrent()
            state.value = state.value.copy(isTransmitting = false)
            runCurrent()
            assertEquals(1, first.presences.size)
            controller.setAway(true, "稍后回来")
            state.value = state.value.copy(playbackMuted = true, microphoneMode = MicrophoneMode.OFF)
            runCurrent()
            assertEquals(OwnPresence(true, "稍后回来", true, true), first.presences.last())
            assertEquals(ControlStatus.ACCEPTED, state.value.presence.outcome?.status)
            state.value = state.value.copy(status = ConnectionStatus(ConnectionPhase.RECONNECTING))
            runCurrent()
            val second = FakeSession()
            controller.attach(second)
            state.value = state.value.copy(status = ConnectionStatus(ConnectionPhase.CONNECTED))
            runCurrent()
            assertEquals(first.presences.last(), second.presences.single())
            controller.close()
        }

    @Test fun rejectsLateInfoAfterCloseOrReplacementConnection() =
        runTest {
            val state = state()
            val controller = SessionControlsController(backgroundScope, state)
            val first = FakeSession()
            val response = CompletableDeferred<Unit>()
            first.infoGate = response
            controller.attach(first)
            runCurrent()
            controller.showChannelInfo(1)
            runCurrent()
            assertTrue(state.value.channelInfo!!.loading)
            controller.dismissChannelInfo()
            response.complete(Unit)
            runCurrent()
            assertNull(state.value.channelInfo)
            val next = CompletableDeferred<Unit>()
            first.infoGate = next
            controller.showChannelInfo(1)
            runCurrent()
            controller.attach(FakeSession())
            next.complete(Unit)
            runCurrent()
            assertNull(state.value.channelInfo)
            controller.close()
        }

    @Test fun unknownPresenceIsVisibleAndOnlyExplicitRetryResubmits() =
        runTest {
            val state = state()
            val session = FakeSession().apply { result = ControlOutcome(ControlStatus.UNKNOWN) }
            val controller = SessionControlsController(backgroundScope, state)
            controller.attach(session)
            runCurrent()
            assertNull(state.value.presence.confirmed)
            assertEquals(ControlStatus.UNKNOWN, state.value.presence.outcome?.status)
            assertEquals(1, session.presences.size)
            controller.retryPresence()
            runCurrent()
            assertEquals(2, session.presences.size)
            controller.close()
        }

    @Test fun permissionChangeResynchronizesIdlePushToTalkWithoutStartingCaptureOrRetryingUnchangedState() =
        runTest {
            val state = state()
            var allowed = false
            val session = FakeSession()
            val controller = SessionControlsController(backgroundScope, state) { allowed }
            controller.attach(session)
            runCurrent()
            assertTrue(session.presences.single().inputMuted)
            allowed = true
            controller.refreshPermission()
            runCurrent()
            assertEquals(2, session.presences.size)
            assertFalse(session.presences.last().inputMuted)
            assertFalse(state.value.isCapturing)
            controller.refreshPermission()
            runCurrent()
            assertEquals(2, session.presences.size)
            allowed = false
            controller.refreshPermission()
            runCurrent()
            assertTrue(session.presences.last().inputMuted)
            controller.close()
        }

    @Test fun replacedPendingPresenceCannotConfirmOldDesiredState() =
        runTest {
            val state = state()
            val first = FakeSession()
            val gate = CompletableDeferred<Unit>()
            first.presenceGate = gate
            val controller = SessionControlsController(backgroundScope, state)
            controller.attach(first)
            runCurrent()
            val second = FakeSession()
            controller.attach(second)
            controller.setAway(true, "new-state")
            gate.complete(Unit)
            runCurrent()
            assertEquals(OwnPresence(away = true, awayMessage = "new-state"), state.value.presence.confirmed)
            controller.close()
        }

    private class FakeSession : Ts3SessionClient {
        val presences = mutableListOf<OwnPresence>()
        var result = ControlOutcome(ControlStatus.ACCEPTED)
        var infoGate: CompletableDeferred<Unit>? = null
        var presenceGate: CompletableDeferred<Unit>? = null

        override suspend fun updatePresence(
            presence: OwnPresence,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): ControlOutcome {
            presences += presence
            presenceGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }

        override suspend fun channelInfo(
            channelId: Int,
            timeoutMs: Long,
            canSubmit: () -> Boolean,
        ): ChannelInfoResult {
            infoGate?.await()
            return ChannelInfoResult(result, ChannelDetails(channelId, "Fixture", "topic", "description", 10, false))
        }

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

        override fun close() = Unit
    }
}
