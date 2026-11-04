package io.github.ts3mobile.audio.opus

internal interface PlaybackReferenceSink {
    fun start()

    fun accepted(
        samples: ShortArray,
        offset: Int,
        count: Int,
        firstFrame: Long,
        acceptedAt: Long,
        timing: RenderTiming,
    )

    fun discard()
}
