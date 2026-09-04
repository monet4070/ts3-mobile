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
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val transmitMutex = Mutex()
    private val pushToTalkPressed = AtomicBoolean(false)
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

    fun setMode(mode: MicrophoneMode) {
        if (mode == MicrophoneMode.CONTINUOUS && !hasPermission()) {
            state.update {
                it.copy(microphoneError = "需要麦克风权限才能开启常开模式")
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
        scope.launch {
            transmitMutex.withLock {
                if (!shouldCapture()) {
                    stopLocked()
                    return@withLock
                }

                if (!hasPermission()) {
                    pushToTalkPressed.set(false)
                    state.update {
                        it.copy(
                            isTransmitting = false,
                            microphoneError = "没有麦克风权限",
                        )
                    }
                    return@withLock
                }

                try {
                    updateForegroundType(true)
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
                    pushToTalkPressed.set(false)
                    microphone.stop()
                    state.update {
                        it.copy(
                            isTransmitting = false,
                            microphoneError = conciseMessage(error),
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
                microphoneError = "需要麦克风权限才能发送语音",
            )
        }
    }

    override fun close() {
        pushToTalkPressed.set(false)
        microphone.close()
    }

    private fun shouldCapture(): Boolean {
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
        diagnosticsRecorder.recordMicrophoneError()
        refreshDiagnostics()
        pushToTalkPressed.set(false)
        state.update {
            it.copy(
                isTransmitting = false,
                microphoneError = conciseMessage(error),
            )
        }
        scope.launch {
            transmitMutex.withLock { stopLocked() }
        }
    }
}
