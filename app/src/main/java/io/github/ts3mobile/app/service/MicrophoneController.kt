package io.github.ts3mobile.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.core.content.ContextCompat
import io.github.ts3mobile.audio.opus.OpusMicrophoneCapture
import io.github.ts3mobile.protocol.Ts3SessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

internal class MicrophoneController(
    context: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    private val diagnosticsRecorder: DiagnosticsRecorder,
    private val refreshDiagnostics: () -> Unit,
    private val updateForegroundType: (includeMicrophone: Boolean) -> Unit,
    private val updateNotification: () -> Unit,
    private val conciseMessage: (Throwable) -> String,
    private val recordEvent: (DiagnosticEventKind) -> Unit = {},
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val transmitMutex = Mutex()
    private val pushToTalkPressed = AtomicBoolean(false)
    private val appVisible = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val microphone = OpusMicrophoneCapture(applicationContext, ::onMicrophoneFailure)

    fun attachTo(session: Ts3SessionClient) {
        session.setVoiceSource(microphone)
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        microphone.setPreferredDevice(device)
    }

    fun resetForConnection() {
        pushToTalkPressed.set(false)
    }

    fun setAppVisible(visible: Boolean) {
        appVisible.set(visible)
        if (visible) reconcile()
    }

    fun setMode(mode: MicrophoneMode) {
        if (mode == MicrophoneMode.CONTINUOUS && !hasPermission()) {
            state.update {
                it.copy(microphoneError = UserMessage.MicrophonePermissionForContinuous)
            }
            return
        }
        if (mode != MicrophoneMode.PUSH_TO_TALK) pushToTalkPressed.set(false)
        state.update {
            it.copy(microphoneMode = mode, microphoneError = null)
        }
        reconcile()
    }

    fun setPushToTalkPressed(pressed: Boolean) {
        val mode = state.value.microphoneMode
        if (mode != MicrophoneMode.PUSH_TO_TALK) {
            pushToTalkPressed.set(false)
            return
        }
        val accepted =
            MicrophoneCapturePolicy.acceptedPushToTalkState(
                mode = mode,
                requestedPressed = pressed,
            )
        pushToTalkPressed.set(accepted)
        if (!accepted) state.update { it.copy(isTransmitting = false) }
        reconcile()
    }

    fun reconcile() {
        if (closed.get()) return
        scope.launch {
            transmitMutex.withLock {
                if (!shouldCapture()) {
                    stopLocked()
                    return@withLock
                }

                if (!hasPermission()) {
                    pushToTalkPressed.set(false)
                    stopLocked()
                    state.update {
                        it.copy(
                            isTransmitting = false,
                            microphoneError = UserMessage.MicrophonePermissionMissing,
                        )
                    }
                    return@withLock
                }

                if (!MicrophoneCapturePolicy.canStartCapture(appVisible.get(), microphone.isCapturing)) {
                    updateForegroundType(false)
                    pushToTalkPressed.set(false)
                    state.update {
                        it.copy(isTransmitting = false, microphoneError = UserMessage.MicrophoneResumeInApp)
                    }
                    recordEvent(DiagnosticEventKind.MICROPHONE_DEFERRED)
                    updateNotification()
                    return@withLock
                }

                if (microphone.isCapturing) return@withLock

                try {
                    updateForegroundType(true)
                    // Visibility may have changed while promoting the service type.
                    if (!appVisible.get()) {
                        updateForegroundType(false)
                        state.update { it.copy(microphoneError = UserMessage.MicrophoneResumeInApp) }
                        recordEvent(DiagnosticEventKind.MICROPHONE_DEFERRED)
                        return@withLock
                    }
                    microphone.start()
                    if (!shouldCapture()) {
                        stopLocked()
                    } else {
                        state.update {
                            it.copy(isTransmitting = true, microphoneError = null)
                        }
                        updateNotification()
                    }
                } catch (error: Throwable) {
                    diagnosticsRecorder.recordMicrophoneError()
                    refreshDiagnostics()
                    recordEvent(DiagnosticEventKind.MICROPHONE_FAILED)
                    pushToTalkPressed.set(false)
                    microphone.stop()
                    state.update {
                        it.copy(
                            isTransmitting = false,
                            microphoneError =
                                if (!appVisible.get()) {
                                    UserMessage.MicrophoneResumeInApp
                                } else {
                                    UserMessage.MicrophoneFailed(conciseMessage(error))
                                },
                        )
                    }
                    updateForegroundType(false)
                }
            }
        }
    }

    fun stopImmediately() {
        pushToTalkPressed.set(false)
        microphone.stop()
        state.update { it.copy(isTransmitting = false) }
        updateForegroundType(false)
    }

    suspend fun stopSerialized() {
        pushToTalkPressed.set(false)
        transmitMutex.withLock { stopLocked() }
    }

    fun reportPermissionDenied() {
        diagnosticsRecorder.recordMicrophoneError()
        refreshDiagnostics()
        pushToTalkPressed.set(false)
        state.update {
            it.copy(
                isTransmitting = false,
                microphoneError = UserMessage.MicrophonePermissionForVoice,
            )
        }
    }

    override fun close() {
        closed.set(true)
        appVisible.set(false)
        pushToTalkPressed.set(false)
        microphone.close()
    }

    private fun shouldCapture(): Boolean {
        if (closed.get()) return false
        val current = state.value
        return MicrophoneCapturePolicy.shouldCapture(
            phase = current.status.phase,
            mode = current.microphoneMode,
            pushToTalkPressed = pushToTalkPressed.get(),
        )
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun stopLocked() {
        microphone.stop()
        state.update { it.copy(isTransmitting = false) }
        updateForegroundType(false)
    }

    private fun onMicrophoneFailure(error: Throwable) {
        recordEvent(DiagnosticEventKind.MICROPHONE_FAILED)
        diagnosticsRecorder.recordMicrophoneError()
        refreshDiagnostics()
        pushToTalkPressed.set(false)
        state.update {
            it.copy(
                isTransmitting = false,
                microphoneError = UserMessage.MicrophoneFailed(conciseMessage(error)),
            )
        }
        scope.launch {
            transmitMutex.withLock { stopLocked() }
        }
    }
}
