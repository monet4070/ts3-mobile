package io.github.ts3mobile.audio.opus

internal data class AudioSampleClock(val framePosition: Long, val nanoTime: Long) {
    init {
        require(framePosition >= 0)
    }

    fun timeAt(frame: Long): Long? {
        if (frame < 0) return null
        val distance = frame - framePosition
        if (distance !in -96_000L..96_000L) return null
        return nanoTime + distance * 1_000_000_000L / 48_000
    }
}

internal enum class AudioTimingQuality { MEASURED, ESTIMATED, UNAVAILABLE }

internal data class RenderTiming(val presentationNanos: Long, val quality: AudioTimingQuality)
