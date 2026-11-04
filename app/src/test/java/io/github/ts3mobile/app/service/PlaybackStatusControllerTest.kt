package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStatusControllerTest {
    @Test fun retirementFailureRemainsDiagnosticButDoesNotPromptDisconnectedUsersToReconnect() {
        val state = MutableStateFlow(TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTED)))
        var failures = 0
        val controller = PlaybackStatusController(state, { failures++ }, {})
        controller.update(PlaybackStatus.FAILED)
        assertTrue(state.value.hasPlaybackFailure)
        for (phase in listOf(ConnectionPhase.DISCONNECTING, ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR)) {
            state.value = state.value.copy(status = ConnectionStatus(phase))
            assertFalse(state.value.hasPlaybackFailure)
        }
        assertEquals(1, failures)
        assertEquals(PlaybackStatus.FAILED, state.value.playbackStatus)
    }

    @Test fun failureIsPublishedAndRecordedOnceAndRecoveryClearsIt() {
        val state = MutableStateFlow(TeamSpeakServiceState())
        var failures = 0
        var notifications = 0
        val controller = PlaybackStatusController(state, { failures++ }, { notifications++ })
        controller.update(PlaybackStatus.FAILED)
        controller.update(PlaybackStatus.FAILED)
        assertEquals(PlaybackStatus.FAILED, state.value.playbackStatus)
        assertEquals(1, failures)
        controller.update(PlaybackStatus.BUFFERING)
        controller.update(PlaybackStatus.PLAYING)
        assertEquals(PlaybackStatus.PLAYING, state.value.playbackStatus)
        assertEquals(3, notifications)
    }
}
