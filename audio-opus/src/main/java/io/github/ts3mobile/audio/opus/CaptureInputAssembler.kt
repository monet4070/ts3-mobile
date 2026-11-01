package io.github.ts3mobile.audio.opus

/** Capture-worker owned. A read spanning an invalidation is discarded by its caller. */
internal class CaptureInputAssembler {
    private val partial = ShortArray(480)
    private var offset = 0
    private var revision = -1L
    val remaining: Int get() = partial.size - offset

    fun prepare(currentRevision: Long) {
        if (revision != currentRevision) {
            offset = 0
            revision = currentRevision
        }
    }

    fun append(
        samples: ShortArray,
        count: Int,
    ): ShortArray? {
        require(count in 0..minOf(samples.size, remaining))
        samples.copyInto(partial, offset, 0, count)
        offset += count
        if (offset < partial.size) return null
        offset = 0
        return partial
    }
}
