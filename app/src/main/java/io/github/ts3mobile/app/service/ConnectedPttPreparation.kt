package io.github.ts3mobile.app.service

import androidx.annotation.MainThread
import io.github.ts3mobile.protocol.sanitizedFailureTypes

@MainThread
internal class ConnectedPttPreparation(
    private val microphone: MicrophoneController,
    private val sessionActive: () -> Boolean,
    private val setForegroundReady: (Boolean) -> Unit,
) {
    private var onDisarmed: (() -> Unit)? = null

    val isValid: Boolean get() = onDisarmed != null && sessionActive() && microphone.floatingPttValid
    val framesProvided: Long get() = microphone.floatingPttFrameCount
    val mode: MicrophoneMode? get() = microphone.floatingPttMode
    val captureRequested: Boolean get() = isValid && microphone.floatingPttCaptureRequested

    fun arm(
        leavingMainActivity: Boolean = false,
        onDisarm: () -> Unit,
    ): Boolean {
        if (onDisarmed != null || !sessionActive() || !microphone.armFloatingPtt(leavingMainActivity)) return false
        return try {
            setForegroundReady(true)
            onDisarmed = onDisarm
            true
        } catch (error: RuntimeException) {
            microphone.disarmFloatingPtt()
            System.err.println("TS3_PTT: preparation failed: ${error.sanitizedFailureTypes()}")
            false
        }
    }

    fun selectMode(mode: MicrophoneMode): Boolean = isValid && microphone.selectFloatingPttMode(mode)

    fun press(): Long? = if (isValid) microphone.pressFloatingPtt() else null

    fun release(token: Long) = microphone.releaseFloatingPtt(token)

    fun disarm() {
        val cleanup = onDisarmed ?: return
        onDisarmed = null
        microphone.disarmFloatingPtt()
        try {
            setForegroundReady(false)
        } catch (error: RuntimeException) {
            System.err.println("TS3_PTT: removing preparation failed: ${error.sanitizedFailureTypes()}")
        } finally {
            cleanup()
        }
    }
}
