package io.github.ts3mobile.audio.opus

internal interface PlaybackOutput : AutoCloseable {
    val playbackHead: Long

    val routeReady: Boolean get() = true

    fun muteImmediately() = Unit

    fun sampleClock(): AudioSampleClock? = null

    fun start()

    fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int

    fun discard()
}
