package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneCaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Capture diagnostics and user messages; resource ownership stays in the controller. */
internal class MicrophoneFeedback(
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    private val diagnostics: DiagnosticsRecorder,
    private val refreshDiagnostics: () -> Unit,
    private val conciseMessage: (Throwable) -> String,
    private val recordEvent: (DiagnosticEventKind) -> Unit,
) {
    fun captureChanged(
        capture: MicrophoneCaptureState,
        sendingEnabled: Boolean,
    ) {
        state.update {
            it.copy(
                isCapturing = capture.capturing,
                isTransmitting = sendingEnabled && capture.transmitting,
                echoCancellationState = capture.echoCancellation,
            )
        }
    }

    fun permissionMissing() {
        state.update { it.copy(isTransmitting = false, isCapturing = false, microphoneError = UserMessage.MicrophonePermissionMissing) }
    }

    fun continuousPermissionRequired() {
        state.update { it.copy(microphoneError = UserMessage.MicrophonePermissionForContinuous) }
    }

    fun refreshPermission(granted: Boolean) {
        if (!granted) return
        state.update {
            when (it.microphoneError) {
                UserMessage.MicrophonePermissionMissing,
                UserMessage.MicrophonePermissionForContinuous,
                UserMessage.MicrophonePermissionForVoice,
                -> it.copy(microphoneError = null)
                else -> it
            }
        }
    }

    fun permissionDenied() {
        diagnostics.recordMicrophoneError()
        refreshDiagnostics()
        state.update {
            it.copy(isTransmitting = false, isCapturing = false, microphoneError = UserMessage.MicrophonePermissionForVoice)
        }
    }

    fun failed(
        error: Throwable,
        captureActive: Boolean,
        deferred: Boolean = false,
    ) {
        diagnostics.recordMicrophoneError()
        refreshDiagnostics()
        recordEvent(DiagnosticEventKind.MICROPHONE_FAILED)
        state.update {
            it.copy(
                isTransmitting = false,
                isCapturing = captureActive,
                microphoneError = if (deferred) UserMessage.MicrophoneResumeInApp else UserMessage.MicrophoneFailed(conciseMessage(error)),
            )
        }
    }
}
