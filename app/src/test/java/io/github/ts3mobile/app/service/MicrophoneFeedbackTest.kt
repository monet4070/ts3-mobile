package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.EchoCancellationState
import io.github.ts3mobile.audio.opus.MicrophoneCaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneFeedbackTest {
    @Test fun permissionRefreshClearsOnlyPermissionMessagesAndPreservesCaptureFailure() {
        val state = MutableStateFlow(TeamSpeakServiceState())
        val feedback = MicrophoneFeedback(state, DiagnosticsRecorder(), {}, { "fixture" }, {})
        feedback.continuousPermissionRequired()
        feedback.refreshPermission(false)
        assertEquals(UserMessage.MicrophonePermissionForContinuous, state.value.microphoneError)
        feedback.refreshPermission(true)
        assertEquals(null, state.value.microphoneError)
        feedback.failed(IllegalStateException("fixture"), captureActive = true)
        feedback.refreshPermission(true)
        assertTrue(state.value.microphoneError is UserMessage.MicrophoneFailed)
        assertTrue(state.value.isCapturing)
        assertFalse(state.value.isTransmitting)
    }

    @Test fun teardownFailureAndDeferredCaptureKeepAccurateResourceStateAndDiagnostics() {
        val state = MutableStateFlow(TeamSpeakServiceState())
        val diagnostics = DiagnosticsRecorder()
        var refreshes = 0
        val events = mutableListOf<DiagnosticEventKind>()
        val feedback = MicrophoneFeedback(state, diagnostics, { refreshes++ }, { "fixture" }, events::add)
        feedback.failed(IllegalStateException("fixture"), captureActive = true)
        assertTrue(state.value.isCapturing)
        feedback.failed(IllegalStateException("fixture"), captureActive = false, deferred = true)
        assertFalse(state.value.isCapturing)
        assertEquals(UserMessage.MicrophoneResumeInApp, state.value.microphoneError)
        assertEquals(2, refreshes)
        assertEquals(listOf(DiagnosticEventKind.MICROPHONE_FAILED, DiagnosticEventKind.MICROPHONE_FAILED), events)
    }

    @Test fun closingSendBoundaryDoesNotPretendTheCaptureResourceHasAlreadyStopped() {
        val state = MutableStateFlow(TeamSpeakServiceState())
        val feedback = MicrophoneFeedback(state, DiagnosticsRecorder(), {}, { "fixture" }, {})
        feedback.captureChanged(MicrophoneCaptureState(true, true, EchoCancellationState.ENABLED), sendingEnabled = false)
        assertTrue(state.value.isCapturing)
        assertFalse(state.value.isTransmitting)
        assertEquals(EchoCancellationState.ENABLED, state.value.echoCancellationState)
        feedback.captureChanged(MicrophoneCaptureState(), sendingEnabled = false)
        assertFalse(state.value.isCapturing)
    }
}
