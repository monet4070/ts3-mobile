package io.github.ts3mobile.audio.opus

/** Owned by one capture only. All gate/queue transitions share a lock. */
internal class CaptureVoiceBuffer {
    private val frames = ArrayDeque<ByteArray>()
    private var active = true
    private var speaking = false
    private var revision = 0L

    @Synchronized fun revision(): Long = revision

    @Synchronized fun publish(
        expectedRevision: Long,
        encoded: List<ByteArray>,
        isSpeaking: Boolean,
    ) {
        if (!active || revision != expectedRevision) return
        for (frame in encoded) {
            if (frames.size == CAPACITY) frames.removeFirst()
            frames.addLast(frame)
        }
        speaking = isSpeaking
    }

    @Synchronized fun isTransmitting(): Boolean = active && (speaking || frames.isNotEmpty())

    @Synchronized fun isReady(): Boolean = active && frames.isNotEmpty()

    @Synchronized fun poll(): ByteArray? = if (active) frames.removeFirstOrNull() else null

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
