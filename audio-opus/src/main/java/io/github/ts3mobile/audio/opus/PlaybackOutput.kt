package io.github.ts3mobile.audio.opus

internal interface PlaybackOutput : AutoCloseable {
    val playbackHead: Long

    fun sampleClock(): AudioSampleClock? = null

    fun start()

    fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int

    fun discard()
}
