package io.github.ts3mobile.audio.opus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.os.Process
import io.github.ts3mobile.protocol.EncodedVoiceSource
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class OpusMicrophoneCapture(
    context: Context,
    private val onFailure: (Throwable) -> Unit = {},
) : EncodedVoiceSource, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val encodedFrames = ArrayBlockingQueue<ByteArray>(ENCODED_QUEUE_CAPACITY)
    private val lifecycleLock = Any()
    private val encodedFrameCount = AtomicLong(0L)
    private val providedFrameCount = AtomicLong(0L)

    @Volatile
    private var control: CaptureControl? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile
    private var worker: Thread? = null

    val isCapturing: Boolean
        get() = control?.running?.get() == true

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredDevice = device
        audioRecord?.let { record ->
            runCatching { record.setPreferredDevice(device) }
                .onFailure { error ->
                    System.err.println(
                        "TS3_AUDIO: failed to route microphone: ${error.sanitizedFailureTypes()}",
                    )
                }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(lifecycleLock) {
            if (control?.running?.get() == true) return
            check(
                applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Microphone permission is not granted" }

            val minimumBytes =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minimumBytes > 0) { "Unable to determine microphone buffer size: $minimumBytes" }

            val encoder = NativeOpusEncoder()
            val denoiser =
                try {
                    NativeRnNoiseProcessor()
                } catch (error: Throwable) {
                    encoder.close()
                    throw error
                }
            val record =
                try {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(max(minimumBytes, CAPTURE_BUFFER_BYTES))
                        .build()
                } catch (error: Throwable) {
                    denoiser.close()
                    encoder.close()
                    throw error
                }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                denoiser.close()
                encoder.close()
                error("Unable to initialize microphone")
            }
            preferredDevice?.let { device ->
                if (!record.setPreferredDevice(device)) {
                    System.err.println("TS3_AUDIO: microphone device preference was rejected")
                }
            }
            val audioEffects = MicrophoneAudioEffects.create(record.audioSessionId)

            try {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Unable to start microphone"
                }
            } catch (error: Throwable) {
                MicrophoneAudioEffects.release(audioEffects)
                record.release()
                denoiser.close()
                encoder.close()
                throw error
            }

            encodedFrames.clear()
            encodedFrameCount.set(0L)
            providedFrameCount.set(0L)
            val newControl = CaptureControl()
            control = newControl
            audioRecord = record
            worker =
                Thread(
                    { captureLoop(record, encoder, denoiser, audioEffects, newControl) },
                    "ts3-opus-capture",
                ).apply { start() }
        }
    }

    fun stop() {
        val activeControl: CaptureControl
        val record: AudioRecord?
        val activeWorker: Thread?
        synchronized(lifecycleLock) {
            activeControl = control ?: run {
                encodedFrames.clear()
                return
            }
            activeControl.running.set(false)
            record = audioRecord
            activeWorker = worker
            encodedFrames.clear()
        }

        runCatching { record?.stop() }
        activeWorker?.interrupt()
        if (activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker?.join(STOP_JOIN_TIMEOUT_MS) }
        }
        synchronized(lifecycleLock) {
            if (control === activeControl) {
                control = null
                worker = null
            }
        }
    }

    override fun isReady(): Boolean = encodedFrames.isNotEmpty()

    override fun pollEncodedFrame(): ByteArray? =
        encodedFrames.poll()?.also { frame ->
            val provided = providedFrameCount.incrementAndGet()
            if (provided == 1L) {
                System.err.println("TS3_AUDIO: provided first microphone Opus frame (${frame.size} bytes)")
            }
        }

    override fun close() = stop()

    private fun captureLoop(
        record: AudioRecord,
        encoder: NativeOpusEncoder,
        denoiser: NativeRnNoiseProcessor,
        audioEffects: List<AudioEffect>,
        captureControl: CaptureControl,
    ) {
        val stats = CaptureSessionStats()
        val denoiserPcm = ShortArray(RNNOISE_FRAME_SAMPLES)
        val opusPcm = ShortArray(OPUS_FRAME_SAMPLES)
        var denoiserOffset = 0
        var opusOffset = 0
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            while (captureControl.running.get()) {
                val read =
                    record.read(
                        denoiserPcm,
                        denoiserOffset,
                        denoiserPcm.size - denoiserOffset,
                        AudioRecord.READ_BLOCKING,
                    )
                if (!captureControl.running.get()) break
                if (read < 0) error("AudioRecord read failed: $read")
                if (read == 0) continue
                denoiserOffset += read
                if (denoiserOffset < denoiserPcm.size) continue

                stats.noteRawFrame(denoiserPcm)
                val denoiseStarted = System.nanoTime()
                val vadProbability = denoiser.processInPlace(denoiserPcm)
                val denoiseNanos = System.nanoTime() - denoiseStarted
                stats.noteDenoisedFrame(denoiserPcm, denoiseNanos, vadProbability)

                denoiserPcm.copyInto(opusPcm, destinationOffset = opusOffset)
                opusOffset += denoiserPcm.size
                denoiserOffset = 0
                if (opusOffset < opusPcm.size) continue

                val encoded = encoder.encode(opusPcm)
                if (!encodedFrames.offer(encoded)) {
                    encodedFrames.poll()
                    encodedFrames.offer(encoded)
                }
                val count = encodedFrameCount.incrementAndGet()
                if (count == 1L) {
                    System.err.println(
                        "TS3_AUDIO: encoded first microphone frame " +
                            "(${encoded.size} bytes, nonZero=${opusPcm.any { it.toInt() != 0 }})",
                    )
                }
                opusOffset = 0
            }
        } catch (error: Throwable) {
            if (captureControl.running.get()) {
                System.err.println(
                    "TS3_AUDIO: microphone capture stopped: ${error.sanitizedFailureTypes()}",
                )
                runCatching { onFailure(error) }
            }
        } finally {
            captureControl.running.set(false)
            encodedFrames.clear()
            val encodedCount = encodedFrameCount.get()
            if (encodedCount > 0L) {
                System.err.println(
                    stats.summaryLine(encodedCount, providedFrameCount.get()),
                )
            }
            runCatching { record.stop() }
            runCatching { record.release() }
            MicrophoneAudioEffects.release(audioEffects)
            denoiser.close()
            encoder.close()
            synchronized(lifecycleLock) {
                if (control === captureControl) control = null
                if (audioRecord === record) audioRecord = null
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private class CaptureControl {
        val running = AtomicBoolean(true)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val RNNOISE_FRAME_SAMPLES = 480
        const val OPUS_FRAME_SAMPLES = 960
        const val FRAME_BYTES = OPUS_FRAME_SAMPLES * 2
        const val CAPTURE_BUFFER_BYTES = FRAME_BYTES * 2
        const val ENCODED_QUEUE_CAPACITY = 3
        const val STOP_JOIN_TIMEOUT_MS = 1_500L
    }
}
