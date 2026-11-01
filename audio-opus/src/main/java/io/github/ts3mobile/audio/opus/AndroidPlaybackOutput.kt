package io.github.ts3mobile.audio.opus

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.max

internal class AndroidPlaybackOutput(
    preferredDevice: AudioDeviceInfo?,
    onRoutingChanged: (AndroidPlaybackOutput) -> Unit,
) : PlaybackOutput {
    private val track: AudioTrack
    private val routingListener: AudioRouting.OnRoutingChangedListener

    @Volatile
    private var routedDeviceId: Int? = null

    init {
        val minimumBytes =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimumBytes > 0) { "Unable to determine audio buffer size: $minimumBytes" }
        track =
            AudioTrack.Builder()
                .setAudioAttributes(voicePlaybackAudioAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimumBytes, TalkerJitterPipeline.MIX_TICK_SAMPLES * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Unable to initialize audio output")
        }
        try {
            setPreferredDevice(preferredDevice)
            routingListener =
                AudioRouting.OnRoutingChangedListener { routing ->
                    val deviceId = routing.routedDevice?.id
                    if (deviceId != null) {
                        val previous = routedDeviceId
                        routedDeviceId = deviceId
                        if (previous != null && previous != deviceId) onRoutingChanged(this)
                    }
                }
            track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        } catch (error: RuntimeException) {
            track.release()
            throw error
        }
    }

    override val playbackHead: Long get() = track.playbackHeadPosition.toLong() and 0xffff_ffffL

    override fun sampleClock(): AudioSampleClock? = AndroidAudioClocks.playback(track)

    override fun start() = track.play()

    override fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int = track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)

    override fun discard() {
        track.pause()
        track.flush()
        routedDeviceId = null
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (!track.setPreferredDevice(device)) {
            System.err.println("TS3_AUDIO: playback device preference was rejected")
        }
    }

    override fun close() {
        try {
            track.removeOnRoutingChangedListener(routingListener)
        } finally {
            track.release()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}

internal fun voicePlaybackAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
