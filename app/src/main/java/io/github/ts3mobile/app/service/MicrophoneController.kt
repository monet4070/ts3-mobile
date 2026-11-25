package io.github.ts3mobile.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicLong
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
    floatingClockMs: () -> Long = SystemClock::elapsedRealtime,
) : AutoCloseable {
    private val transmitMutex = Mutex()
    private val transmitPublicationLock = Any()
    private val pushToTalkPressed = AtomicBoolean(false)
    private val appVisible = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val feedback = MicrophoneFeedback(state, diagnosticsRecorder, refreshDiagnostics, conciseMessage, recordEvent)
    private val microphone = captureFactory(context.applicationContext, ::onMicrophoneFailure, ::onCaptureState)
    val levels = observeMicrophoneLevels(microphone)
    private val voiceSource = MicrophoneVoiceSource(microphone)
    private val stopPending = AtomicBoolean(false)
    private val floating = FloatingPttPresses(floatingClockMs) { state.value.floatingPttTarget() }
    private val floatingFrames = AtomicLong()
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
        disarmFloatingPtt()
        pushToTalkPressed.set(false)
    }

    suspend fun isInputReleasedForTrial(): Boolean =
        withContext(captureDispatcher) {
            transmitMutex.withLock {
                !closed.get() && state.value.microphoneTrialAccess == MicrophoneTrialAccess.READY && microphone.isReleased
            }
        }

    fun setAppVisible(visible: Boolean) {
        if (visible) disarmFloatingPtt()
        appVisible.set(visible)
        if (visible) reconcile()
    }

    fun refreshPermission() {
        val granted = hasPermission()
        if (!granted) disarmFloatingPtt()
        feedback.refreshPermission(granted)
    }

    fun setMode(mode: MicrophoneMode) {
        if (mode.requiresContinuousCapture && !hasPermission()) {
            feedback.continuousPermissionRequired()
            return
        }
        if (mode != MicrophoneMode.PUSH_TO_TALK) pushToTalkPressed.set(false)
        if (mode != MicrophoneMode.PUSH_TO_TALK) disarmFloatingPtt()
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
        val captureMode = floating.capture?.mode ?: state.value.microphoneMode
        microphone.configure(settings, captureMode == MicrophoneMode.VOLUME_GATE)
    }

    private fun onCaptureState(capture: MicrophoneCaptureState) {
        lastCaptureState = capture
        synchronized(transmitPublicationLock) { feedback.captureChanged(capture, voiceSource.isEnabled()) }
        if (!capture.capturing) {
            updateForegroundType(false)
            afterRelease.getAndSet(null)?.invoke()
        }
        scope.launch { updateNotification() }
    }

    fun setPushToTalkPressed(pressed: Boolean) {
        if (floating.isArmed) return
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

    val floatingPttValid: Boolean get() = floating.isValid && hasPermission() && !closed.get()
    val floatingPttInputReleased: Boolean get() = microphone.isReleased
    val floatingPttFrameCount: Long get() = floatingFrames.get()
    val floatingPttMode: MicrophoneMode? get() = floating.mode
    val floatingPttCaptureRequested: Boolean get() = floating.capture != null

    fun armFloatingPtt(leavingMainActivity: Boolean = false): Boolean =
        whenInputReleased { appVisible.get() == leavingMainActivity && floating.arm(state.value.microphoneMode) } == true

    fun selectFloatingPttMode(mode: MicrophoneMode): Boolean {
        if (appVisible.get() || !floatingPttValid || !floating.select(mode, ::requestStop)) return false
        if (mode.requiresContinuousCapture) reconcile()
        return true
    }

    fun pressFloatingPtt(): Long? {
        if (appVisible.get()) return null
        val token = whenInputReleased { floating.begin() } ?: return null
        reconcile()
        return token
    }

    fun releaseFloatingPtt(token: Long) = floating.release(token, ::requestStop)

    fun disarmFloatingPtt() {
        if (floating.disarm()) requestStop()
    }

    private fun <Result> whenInputReleased(action: () -> Result?): Result? {
        if (!transmitMutex.tryLock()) return null
        return try {
            if (closed.get() || pushToTalkPressed.get() || stopPending.get()) return null
            if (!hasPermission() || !microphone.isReleased) return null
            action()
        } finally {
            transmitMutex.unlock()
        }
    }

    fun reconcile() {
        if (closed.get()) return
        scope.launch(captureDispatcher) {
            transmitMutex.withLock {
                if (stopPending.getAndSet(false)) stopLocked()
                val floatingPress = floating.capture
                if (!shouldCapture(floatingPress)) {
                    stopLocked()
                    return@withLock
                }

                if (!hasPermission()) {
                    floating.disarm()
                    pushToTalkPressed.set(false)
                    stopLocked()
                    feedback.permissionMissing()
                    return@withLock
                }

                if (floatingPress == null && !MicrophoneCapturePolicy.canStartCapture(appVisible.get(), microphone.isCapturing)) {
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
                    if (floatingPress == null) updateForegroundType(true)
                    // Visibility may have changed while promoting the service type.
                    if (floatingPress == null && !appVisible.get()) {
                        updateForegroundType(false)
                        state.update { it.copy(microphoneError = UserMessage.MicrophoneResumeInApp) }
                        recordEvent(DiagnosticEventKind.MICROPHONE_DEFERRED)
                        return@withLock
                    }
                    if (!shouldCapture(floatingPress)) {
                        stopLocked()
                        return@withLock
                    }
                    if (floatingPress != null) check(microphone.isReleased) { "Previous microphone input is not released" }
                    val current = state.value
                    val captureMode = floatingPress?.mode ?: current.microphoneMode
                    microphone.configure(current.microphoneSettings, captureMode == MicrophoneMode.VOLUME_GATE)
                    microphone.start()
                    val authorized = floatingPress?.let { token -> { shouldCapture(token) } }
                    val countFrame: (() -> Unit)? = if (floatingPress != null) ({ floatingFrames.incrementAndGet() }) else null
                    val stillAuthorized = shouldCapture(floatingPress) && !stopPending.get()
                    if (!stillAuthorized || !voiceSource.tryEnable(sendRevision, authorized, countFrame)) {
                        stopLocked()
                        if (shouldCapture()) reconcile()
                    } else {
                        onCaptureState(lastCaptureState)
                        state.update {
                            it.copy(microphoneError = null)
                        }
                        updateNotification()
                    }
                } catch (error: Throwable) {
                    voiceSource.disable()
                    floating.disarm()
                    pushToTalkPressed.set(false)
                    stopSafely()
                    feedback.failed(error, !microphone.isReleased, deferred = !appVisible.get())
                }
            }
        }
    }

    fun stopImmediately() {
        pushToTalkPressed.set(false)
        floating.disarm()
        requestStop()
    }

    private fun requestStop() {
        synchronized(transmitPublicationLock) {
            voiceSource.disable()
            state.update { it.copy(isTransmitting = false) }
        }
        microphone.requestStop()
        stopPending.set(true)
        scope.launch(captureDispatcher) {
            transmitMutex.withLock {
                if (stopPending.getAndSet(false)) stopSafely()
            }
        }
    }

    suspend fun stopSerialized() {
        pushToTalkPressed.set(false)
        floating.disarm()
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
        disarmFloatingPtt()
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
        floating.disarm()
        voiceSource.disable()
        microphone.requestStop()
        scope.launch(NonCancellable + captureDispatcher) {
            transmitMutex.withLock {
                stopSafely()
                if (microphone.isReleased) afterRelease.getAndSet(null)?.invoke()
            }
        }
    }

    private fun shouldCapture(floatingPress: FloatingPttCapture? = floating.capture): Boolean {
        if (closed.get()) return false
        if (floatingPress != null) return !appVisible.get() && floating.capture == floatingPress && hasPermission()
        if (floating.isArmed) return false
        val current = state.value
        return MicrophoneCapturePolicy.shouldCapture(
            phase = current.status.phase,
            mode = current.microphoneMode,
            pushToTalkPressed = pushToTalkPressed.get(),
        )
    }

    fun takeCaptureOverflow(): Long = (microphone as? OpusMicrophoneCapture)?.takeCaptureOverflow() ?: 0L

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
        floating.disarm()
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
