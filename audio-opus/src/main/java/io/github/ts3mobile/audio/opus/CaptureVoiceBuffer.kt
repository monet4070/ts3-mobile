package io.github.ts3mobile.audio.opus

import io.github.ts3mobile.protocol.TimedEncodedVoiceFrame

/** Owned by one capture only. All gate/queue transitions share a lock. */
internal class CaptureVoiceBuffer {
    private val frames = ArrayDeque<TimedEncodedVoiceFrame>()
    private var active = true
    private var speaking = false
    private var revision = 0L

    @Synchronized fun revision(): Long = revision

    @Synchronized fun publish(
        expectedRevision: Long,
        encoded: List<ByteArray>,
        isSpeaking: Boolean,
        encodedTimestamps: List<Long> = emptyList(),
    ): Int {
        if (!active || revision != expectedRevision) return 0
        var dropped = 0
        for (index in encoded.indices) {
            if (frames.size == CAPACITY) {
                frames.removeFirst()
                dropped++
            }
            frames.addLast(TimedEncodedVoiceFrame(encoded[index], encodedTimestamps.getOrNull(index)))
        }
        speaking = isSpeaking
        return dropped
    }

    @Synchronized fun isTransmitting(): Boolean = active && (speaking || frames.isNotEmpty())

    @Synchronized fun isReady(): Boolean = active && frames.isNotEmpty()

    fun poll(): ByteArray? = pollTimed()?.bytes

    @Synchronized fun pollTimed(): TimedEncodedVoiceFrame? = if (active) frames.removeFirstOrNull() else null

    @Synchronized fun reset() {
        revision++
        frames.clear()
        speaking = false
    }

    @Synchronized fun stop() {
        active = false
        reset()
    }

    companion object {
        const val CAPACITY = MicrophoneFrameProcessor.ONSET_FRAMES + 3
    }
}
