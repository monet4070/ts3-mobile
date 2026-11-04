package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneCapture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MicrophoneTeardown(
    private val microphone: MicrophoneCapture,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val onTimeout: () -> Unit,
    private val onReleased: () -> Unit,
) {
    fun stop() {
        val watchdog =
            scope.launch(NonCancellable + dispatcher) {
                delay(1500)
                if (!microphone.isReleased) {
                    System.err.println("TS3_AUDIO: microphone teardown still pending; foreground type retained")
                    onTimeout()
                }
            }
        try {
            microphone.stop()
            check(microphone.isReleased) { "Microphone resources are still active" }
            onReleased()
        } finally {
            watchdog.cancel()
        }
    }
}
