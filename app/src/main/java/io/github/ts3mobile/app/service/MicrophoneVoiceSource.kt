package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.TimedEncodedVoiceFrame
import io.github.ts3mobile.protocol.TimedEncodedVoiceSource
import java.util.concurrent.atomic.AtomicReference

/** The send boundary closes synchronously before potentially blocking capture teardown. */
internal class MicrophoneVoiceSource(private val capture: EncodedVoiceSource) : TimedEncodedVoiceSource {
    private data class Gate(val revision: Long, val authorized: (() -> Boolean)? = null, val onFrame: (() -> Unit)? = null) {
        fun isOpen(): Boolean = revision and 1L == 1L && authorized?.invoke() != false
    }

    private val gate = AtomicReference(Gate(0L))

    fun disable() {
        gate.updateAndGet { Gate((it.revision + 2) and -2L) }
    }

    fun currentRevision(): Long = gate.get().revision

    fun tryEnable(
        expectedRevision: Long,
        authorized: (() -> Boolean)? = null,
        onFrame: (() -> Unit)? = null,
    ): Boolean {
        val current = gate.get()
        return current.revision == expectedRevision && gate.compareAndSet(current, Gate(expectedRevision or 1L, authorized, onFrame))
    }

    fun isEnabled(): Boolean = gate.get().isOpen()

    override val isTransmitting: Boolean
        get() = isEnabled() && capture.isTransmitting

    override fun isReady(): Boolean = isEnabled() && capture.isReady()

    override fun pollTimedEncodedFrame(): TimedEncodedVoiceFrame? {
        val current = gate.get()
        if (!current.isOpen()) return null
        val frame =
            if (capture is TimedEncodedVoiceSource) {
                capture.pollTimedEncodedFrame()
            } else {
                capture.pollEncodedFrame()?.let { TimedEncodedVoiceFrame(it, null) }
            }
        if (gate.get() !== current || !current.isOpen()) return null
        if (frame != null && frame.bytes.isNotEmpty()) current.onFrame?.invoke()
        return frame
    }
}
