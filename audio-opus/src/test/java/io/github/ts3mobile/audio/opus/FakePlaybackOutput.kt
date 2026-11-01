package io.github.ts3mobile.audio.opus

internal class FakePlaybackOutput : PlaybackOutput {
    override var playbackHead = 0L
    var timestamp: AudioSampleClock? = null

    override fun sampleClock(): AudioSampleClock? = timestamp

    val accepted = mutableListOf<Short>()
    val writeResults = ArrayDeque<Int>()
    var recordSamples = true
    var acceptedFrames = 0L
    var maxWriteSamples = Int.MAX_VALUE
    var writeCalls = 0
    var discardCount = 0
    var startCount = 0
    var closed = false
    var failClose = false

    override fun start() {
        check(!closed)
        startCount++
    }

    override fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int {
        check(!closed)
        writeCalls++
        val written = if (writeResults.isNotEmpty()) writeResults.removeFirst() else minOf(count, maxWriteSamples)
        if (written in 1..count) {
            acceptedFrames += written
            if (recordSamples) accepted.addAll(samples.copyOfRange(offset, offset + written).toList())
        }
        return written
    }

    override fun discard() {
        discardCount++
        playbackHead = 0L
    }

    override fun close() {
        check(!failClose) { "Synthetic release failure" }
        closed = true
    }
}
