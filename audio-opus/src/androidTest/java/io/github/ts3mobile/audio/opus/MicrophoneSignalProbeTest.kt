package io.github.ts3mobile.audio.opus

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Explicitly opt-in local measurements. Never connects to a server or transmits
 * microphone audio. Raw PCM stays in the test application's private directory.
 * These measurements characterize a signal; they do not assert perceived quality.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneSignalProbeTest {
    @Test
    fun processesProvidedSpeechWithProductionDenoiser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = File(instrumentation.targetContext.filesDir, "speech.pcm")
        assumeTrue("Supply a mono PCM16 48kHz speech fixture to opt in", input.isFile)
        val bytes = input.readBytes()
        require(bytes.isNotEmpty() && bytes.size % (FRAME_SAMPLES * 2) == 0)
        val output = File(instrumentation.targetContext.filesDir, "speech-denoised.pcm")
        val stats = CaptureSessionStats()
        NativeRnNoiseProcessor().use { denoiser ->
            FileOutputStream(output).use { stream ->
                for (offset in bytes.indices step FRAME_SAMPLES * 2) {
                    val pcm =
                        ShortArray(FRAME_SAMPLES) { index ->
                            (
                                (bytes[offset + index * 2].toInt() and 0xff) or
                                    (bytes[offset + index * 2 + 1].toInt() shl 8)
                            ).toShort()
                        }
                    stats.noteRawFrame(pcm)
                    val started = System.nanoTime()
                    val vad = denoiser.processInPlace(pcm)
                    stats.noteDenoisedFrame(pcm, System.nanoTime() - started, vad)
                    stream.writePcm(pcm)
                }
                // Include the denoiser's delayed tail for aligned comparisons.
                repeat(3) {
                    val tail = ShortArray(FRAME_SAMPLES)
                    denoiser.processInPlace(tail)
                    stream.writePcm(tail)
                }
            }
        }
        assertTrue(output.length() > input.length())
        Log.i(TAG, stats.summaryLine(bytes.size / (FRAME_SAMPLES * 2L), 0))
        writeOpusRoundTrip(input, File(instrumentation.targetContext.filesDir, "speech-opus.pcm"))
        writeOpusRoundTrip(output, File(instrumentation.targetContext.filesDir, "speech-denoised-opus.pcm"))
    }

    @SuppressLint("MissingPermission")
    @Test
    fun capturesPairedRawAndDenoisedMicrophoneSignal() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Microphone measurement requires explicit opt-in", arguments.getString("captureProbe") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val variant = arguments.getString("source", "voice")
        require(variant in setOf("voice", "mic", "voice-agc"))
        val frames = arguments.getString("frames", "2400").toInt()
        require(frames in 100..6000)
        val nonce = arguments.getString("nonce", "manual")
        require(nonce.matches(Regex("[a-zA-Z0-9-]+")))
        val source =
            if (variant == "mic") MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val oldMode = manager.mode
        val stats = CaptureSessionStats()
        var recordingStarted = false
        val monitor = instrumentation.addMonitor(MicrophoneProbeActivity::class.java.name, null, false)
        // Launch as shell so OEM background-launch restrictions cannot silently
        // invalidate a comparison of microphone sources.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${MicrophoneProbeActivity::class.java.name}",
        ).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val activity = requireNotNull(monitor.waitForActivityWithTimeout(5_000)) { "Probe activity did not resume" }
        instrumentation.removeMonitor(monitor)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO)
        val record =
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(
                    max(
                        AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
                        FRAME_SAMPLES * 8,
                    ),
                )
                .build()
        val effects = MicrophoneAudioEffects.create(record.audioSessionId)
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, record.state)
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (variant == "voice-agc") {
                effects.filterIsInstance<AutomaticGainControl>().forEach { effect -> effect.enabled = true }
            }
            effects.forEach { effect ->
                Log.i(TAG, "effect type=${effect.descriptor.type} enabled=${effect.enabled} control=${effect.hasControl()}")
            }
            Log.i(
                TAG,
                "platformNsAvailable=${NoiseSuppressor.isAvailable()} " +
                    "platformAgcAvailable=${AutomaticGainControl.isAvailable()} source=$source",
            )
            record.startRecording()
            recordingStarted = true
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
            Log.i(TAG, "READY nonce=$nonce variant=$variant")
            NativeRnNoiseProcessor().use { denoiser ->
                FileOutputStream(File(context.filesDir, "$variant-raw.pcm")).use { raw ->
                    FileOutputStream(File(context.filesDir, "$variant-denoised.pcm")).use { processed ->
                        repeat(frames) { index ->
                            val pcm = ShortArray(FRAME_SAMPLES)
                            var offset = 0
                            while (offset < pcm.size) {
                                val count = record.read(pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING)
                                check(count > 0) { "Microphone read failed: $count" }
                                offset += count
                            }
                            raw.writePcm(pcm)
                            stats.noteRawFrame(pcm)
                            val started = System.nanoTime()
                            val vad = denoiser.processInPlace(pcm)
                            stats.noteDenoisedFrame(pcm, System.nanoTime() - started, vad)
                            processed.writePcm(pcm)
                            if (index == ROUTE_SETTLE_FRAMES) {
                                Log.i(TAG, "routeType=${record.routedDevice?.type} requestedRate=${record.sampleRate}")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    manager.activeRecordingConfigurations
                                        .filter { it.clientAudioSessionId == record.audioSessionId }
                                        .forEach { configuration ->
                                            Log.i(
                                                TAG,
                                                "clientRate=${configuration.clientFormat.sampleRate} " +
                                                    "deviceRate=${configuration.format.sampleRate} " +
                                                    "deviceType=${configuration.audioDevice?.type}",
                                            )
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                assertFalse("System silenced the probe microphone", configuration.isClientSilenced)
                                                Log.i(
                                                    TAG,
                                                    "clientEffectTypes=${configuration.clientEffects.map { it.type }} " +
                                                        "deviceEffectTypes=${configuration.effects.map { it.type }}",
                                                )
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("Microphone delivered only zero samples", stats.capturedNonZero())
            Log.i(TAG, "variant=$variant ${stats.summaryLine(frames.toLong(), 0)}")
        } finally {
            if (recordingStarted) record.stop()
            record.release()
            MicrophoneAudioEffects.release(effects)
            manager.mode = oldMode
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun FileOutputStream.writePcm(pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        write(bytes)
    }

    private fun writeOpusRoundTrip(
        input: File,
        output: File,
    ) {
        val bytes = input.readBytes()
        val timings = mutableListOf<Long>()
        NativeOpusEncoder().use { encoder ->
            NativeOpusDecoder().use { decoder ->
                FileOutputStream(output).use { stream ->
                    // Pad the final frame and include enough silence for codec delay.
                    val frames = (bytes.size + OPUS_FRAME_SAMPLES * 2 - 1) / (OPUS_FRAME_SAMPLES * 2) + 3
                    repeat(frames) { frame ->
                        val pcm =
                            ShortArray(OPUS_FRAME_SAMPLES) { index ->
                                val offset = (frame * OPUS_FRAME_SAMPLES + index) * 2
                                if (offset + 1 < bytes.size) {
                                    ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort()
                                } else {
                                    0
                                }
                            }
                        val started = System.nanoTime()
                        val encoded = encoder.encode(pcm)
                        timings += System.nanoTime() - started
                        stream.writePcm(decoder.decode(encoded, OPUS_FRAME_SAMPLES))
                    }
                }
            }
        }
        timings.sort()
        Log.i(TAG, "opusEncodeP95Us=${timings[timings.size * 95 / 100] / 1000} frames=${timings.size}")
    }

    private companion object {
        const val TAG = "TS3_AUDIO_PROBE"
        const val SAMPLE_RATE = 48_000
        const val FRAME_SAMPLES = 480
        const val OPUS_FRAME_SAMPLES = 960
        const val ROUTE_SETTLE_FRAMES = 100
    }
}
