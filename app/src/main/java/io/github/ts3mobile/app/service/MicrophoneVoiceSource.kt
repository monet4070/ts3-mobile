package io.github.ts3mobile.app.service

import io.github.ts3mobile.protocol.EncodedVoiceSource
import java.util.concurrent.atomic.AtomicLong

/** The send boundary closes synchronously before potentially blocking capture teardown. */
internal class MicrophoneVoiceSource(private val capture: EncodedVoiceSource) : EncodedVoiceSource {
    private val revision = AtomicLong()

    fun disable() {
        revision.updateAndGet { (it + 2) and -2L }
    }

    fun currentRevision(): Long = revision.get()

    fun tryEnable(expectedRevision: Long): Boolean = revision.compareAndSet(expectedRevision, expectedRevision or 1L)

    fun isEnabled(): Boolean = revision.get() and 1L == 1L

    override val isTransmitting: Boolean
        get() = isEnabled() && capture.isTransmitting

    override fun isReady(): Boolean = isEnabled() && capture.isReady()

    override fun pollEncodedFrame(): ByteArray? {
        val current = revision.get()
        if (current and 1L == 0L) return null
        val frame = capture.pollEncodedFrame()
        return frame.takeIf { revision.get() == current }
    }
}
