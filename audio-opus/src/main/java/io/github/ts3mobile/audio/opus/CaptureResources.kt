package io.github.ts3mobile.audio.opus

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import kotlin.math.max

internal class CaptureResources private constructor(
    val record: AudioRecord,
    val encoder: NativeOpusEncoder,
    val denoiser: NativeRnNoiseProcessor,
    val effects: MicrophoneAudioEffects,
) : AutoCloseable {
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    @Volatile var released = false
        private set

    fun listen(listener: AudioRouting.OnRoutingChangedListener) {
        record.addOnRoutingChangedListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        routingListener = listener
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Unable to start microphone" }
    }

    @Synchronized fun stopRecording() = cleanup("stop microphone") { record.stop() }

    @Synchronized override fun close() {
        if (released) return
        stopRecording()
        routingListener?.let { listener -> cleanup("remove capture routing listener") { record.removeOnRoutingChangedListener(listener) } }
        routingListener = null
        val recordReleased = cleanup("release microphone") { record.release() }
        val effectsReleased = cleanup("release capture effects", effects::close)
        val denoiserReleased = cleanup("release denoiser", denoiser::close)
        val encoderReleased = cleanup("release encoder", encoder::close)
        released = recordReleased && effectsReleased && denoiserReleased && encoderReleased
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun create(
            preferred: AudioDeviceInfo?,
            settings: MicrophoneSettings,
        ): CaptureResources {
            val rollback = ArrayDeque<AutoCloseable>()
            try {
                val encoder = NativeOpusEncoder().also(rollback::addFirst)
                val denoiser = NativeRnNoiseProcessor().also(rollback::addFirst)
                val minimum = AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unable to determine microphone buffer size" }
                val record =
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(
                            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                        )
                        .setBufferSizeInBytes(max(minimum, 3840)).build()
                rollback.addFirst(AutoCloseable { record.release() })
                check(record.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize microphone" }
                if (preferred != null && !record.setPreferredDevice(preferred)) {
                    System.err.println("TS3_AUDIO: microphone device preference was rejected")
                }
                val effects = MicrophoneAudioEffects(record.audioSessionId).also(rollback::addFirst)
                effects.configure(settings.echoCancellation)
                return CaptureResources(record, encoder, denoiser, effects)
            } catch (error: Throwable) {
                rollback.forEach { resource -> cleanup("rollback capture resource", resource::close) }
                throw error
            }
        }

        private fun cleanup(
            operation: String,
            action: () -> Unit,
        ): Boolean =
            runCatching(action).onFailure { error ->
                System.err.println("TS3_AUDIO: failed to $operation: ${error.sanitizedFailureTypes()}")
            }.isSuccess
    }
}
