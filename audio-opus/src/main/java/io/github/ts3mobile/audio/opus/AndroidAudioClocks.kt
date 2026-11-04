package io.github.ts3mobile.audio.opus

import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack

internal object AndroidAudioClocks {
    fun capture(record: AudioRecord): AudioSampleClock? {
        val timestamp = AudioTimestamp()
        val available = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        return if (available && timestamp.framePosition >= 0) {
            AudioSampleClock(timestamp.framePosition, timestamp.nanoTime)
        } else {
            null
        }
    }

    fun playback(track: AudioTrack): AudioSampleClock? {
        val timestamp = AudioTimestamp()
        return if (track.getTimestamp(timestamp) && timestamp.framePosition >= 0) {
            AudioSampleClock(
                timestamp.framePosition,
                timestamp.nanoTime,
            )
        } else {
            null
        }
    }
}
