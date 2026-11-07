package io.github.ts3mobile.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import androidx.core.content.ContextCompat
import io.github.ts3mobile.audio.opus.MicrophoneCapture
import io.github.ts3mobile.audio.opus.MicrophoneCaptureState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.audio.opus.OpusMicrophoneCapture
import io.github.ts3mobile.protocol.Ts3SessionClient
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class MicrophoneController(
    context: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<TeamSpeakServiceState>,
    diagnosticsRecorder: DiagnosticsRecorder,
    refreshDiagnostics: () -> Unit,
    private val updateForegroundType: (includeMicrophone: Boolean) -> Unit,
    private val updateNotification: () -> Unit,
    conciseMessage: (Throwable) -> String,
    private val recordEvent: (DiagnosticEventKind) -> Unit = {},
    captureFactory: (Context, (Throwable) -> Unit, (MicrophoneCaptureState) -> Unit) -> MicrophoneCapture = ::OpusMicrophoneCapture,
    private val permissionGranted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val transmitMutex = Mutex()
    private val pushToTalkPressed = AtomicBoolean(false)
    private val appVisible = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val feedback = MicrophoneFeedback(state, diagnosticsRecorder, refreshDiagnostics, conciseMessage, recordEvent)
    private val microphone = captureFactory(context.applicationContext, ::onMicrophoneFailure, ::onCaptureState)
    val levels = observeMicrophoneLevels(microphone)
    private val voiceSource = MicrophoneVoiceSource(microphone)
    private val stopPending = AtomicBoolean(false)
    private val afterRelease = AtomicReference<(() -> Unit)?>(null)
    private val teardown =
        MicrophoneTeardown(
            microphone,
            scope,
            captureDispatcher,
            { recordEvent(DiagnosticEventKind.MICROPHONE_STOP_TIMEOUT) },
            {
                state.update { it.copy(isTransmitting = false, isCapturing = false) }
                updateForegroundType(false)
            },
        )

    @Volatile private var lastCaptureState = MicrophoneCaptureState()

    fun attachTo(session: Ts3SessionClient) {
        session.setVoiceSource(voiceSource)
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        microphone.setPreferredDevice(device)
    }

    fun invalidateRouting() = microphone.invalidateRouting()

    fun resetForConnection() {
        pushToTalkPressed.set(false)
    }

    fun setAppVisible(visible: Boolean) {
        appVisible.set(visible)
        if (visible) reconcile()
    }

    fun refreshPermission() = feedback.refreshPermission(hasPermission())

    fun setMode(mode: MicrophoneMode) {
        if (mode.requiresContinuousCapture && !hasPermission()) {
            feedback.continuousPermissionRequired()
            return
        }
        if (mode != MicrophoneMode.PUSH_TO_TALK) pushToTalkPressed.set(false)
        state.update {
            it.copy(microphoneMode = mode, microphoneError = null)
        }
        if (!shouldCapture()) {
            stopImmediately()
            return
        }
        microphone.configure(state.value.microphoneSettings, mode == MicrophoneMode.VOLUME_GATE)
        reconcile()
    }

    fun setSettings(settings: MicrophoneSettings) {
        state.update { it.copy(microphoneSettings = settings) }
        microphone.configure(settings, state.value.microphoneMode == MicrophoneMode.VOLUME_GATE)
    }

    private fun onCaptureState(capture: MicrophoneCaptureState) {
        lastCaptureState = capture
        feedback.captureChanged(capture, voiceSource.isEnabled())
        if (!capture.capturing) {
            updateForegroundType(false)
            afterRelease.getAndSet(null)?.invoke()
        }
        scope.launch { updateNotification() }
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
        if (!accepted) state.update { it.copy(isTransmitting = false, isCapturing = false) }
        if (!accepted) stopImmediately() else reconcile()
    }

    fun reconcile() {
        if (closed.get()) return
        scope.launch(captureDispatcher) {
            transmitMutex.withLock {
                if (stopPending.getAndSet(false)) stopLocked()
                if (!shouldCapture()) {
                    stopLocked()
                    return@withLock
                }

                if (!hasPermission()) {
                    pushToTalkPressed.set(false)
                    stopLocked()
                    feedback.permissionMissing()
                    return@withLock
                }

                if (!MicrophoneCapturePolicy.canStartCapture(appVisible.get(), microphone.isCapturing)) {
                    updateForegroundType(false)
                    pushToTalkPressed.set(false)
                    state.update {
                        it.copy(
                            isTransmitting = false,
                            isCapturing = false,
                            microphoneError = UserMessage.MicrophoneResumeInApp,
                        )
                    }
                    recordEvent(DiagnosticEventKind.MICROPHONE_DEFERRED)
                    updateNotification()
                    return@withLock
                }

                if (microphone.isCapturing) return@withLock

                val sendRevision = voiceSource.currentRevision()
                try {
                    updateForegroundType(true)
                    // Visibility may have changed while promoting the service type.
                    if (!appVisible.get()) {
                        updateForegroundType(false)
                        state.update { it.copy(microphoneError = UserMessage.MicrophoneResumeInApp) }
                        recordEvent(DiagnosticEventKind.MICROPHONE_DEFERRED)
                        return@withLock
                    }
                    if (!shouldCapture()) {
                        stopLocked()
                        return@withLock
                    }
                    microphone.configure(state.value.microphoneSettings, state.value.microphoneMode == MicrophoneMode.VOLUME_GATE)
                    microphone.start()
                    if (!shouldCapture() || stopPending.get()) {
                        stopLocked()
                        if (shouldCapture()) reconcile()
                    } else {
                        if (!voiceSource.tryEnable(sendRevision)) {
                            stopLocked()
                            if (shouldCapture()) reconcile()
                            return@withLock
                        }
                        onCaptureState(lastCaptureState)
                        state.update {
                            it.copy(microphoneError = null)
                        }
                        updateNotification()
                    }
                } catch (error: Throwable) {
                    voiceSource.disable()
                    pushToTalkPressed.set(false)
                    stopSafely()
                    feedback.failed(error, !microphone.isReleased, deferred = !appVisible.get())
                }
            }
        }
    }

    fun stopImmediately() {
        pushToTalkPressed.set(false)
        voiceSource.disable()
        microphone.requestStop()
        stopPending.set(true)
        state.update { it.copy(isTransmitting = false) }
        scope.launch(captureDispatcher) {
            transmitMutex.withLock {
                if (stopPending.getAndSet(false)) stopSafely()
            }
        }
    }

    suspend fun stopSerialized() {
        pushToTalkPressed.set(false)
        voiceSource.disable()
        withContext(captureDispatcher) {
            transmitMutex.withLock {
                stopPending.set(false)
                runInterruptible { stopLocked() }
            }
        }
    }

    fun reportPermissionDenied() {
        pushToTalkPressed.set(false)
        feedback.permissionDenied()
    }

    override fun close() {
        closeAfterRelease {}
    }

    fun closeAfterRelease(onReleased: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        afterRelease.set(onReleased)
        appVisible.set(false)
        pushToTalkPressed.set(false)
        voiceSource.disable()
        microphone.requestStop()
        scope.launch(NonCancellable + captureDispatcher) {
            transmitMutex.withLock {
                stopSafely()
                if (microphone.isReleased) afterRelease.getAndSet(null)?.invoke()
            }
        }
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

    private fun hasPermission(): Boolean = permissionGranted()

    private fun stopLocked() {
        voiceSource.disable()
        teardown.stop()
    }

    private fun stopSafely() {
        try {
            stopLocked()
        } catch (error: Exception) {
            System.err.println("TS3_AUDIO: capture teardown failed: ${error.sanitizedFailureTypes()}")
            feedback.failed(error, !microphone.isReleased)
        }
    }

    private fun onMicrophoneFailure(error: Throwable) {
        voiceSource.disable()
        val failedGeneration = microphone.generation
        pushToTalkPressed.set(false)
        feedback.failed(error, !microphone.isReleased)
        scope.launch(captureDispatcher) {
            transmitMutex.withLock {
                if (microphone.generation == failedGeneration) stopLocked()
            }
        }
    }
}
