package io.github.ts3mobile.app.probe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

internal data class ProbeCaptureOutcome(
    val startedMs: Long?,
    val finishedMs: Long,
    val usableInput: Boolean,
    val failed: Boolean,
)

internal class ProbeRecorder {
    @SuppressLint("MissingPermission")
    fun capture(
        held: AtomicBoolean,
        onStarted: (Long) -> Unit,
    ): ProbeCaptureOutcome {
        val samples = ShortArray(320)
        var recorder: AudioRecord? = null
        var started: Long? = null
        var effective = false
        var failed = false
        try {
            if (held.get()) {
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                recorder =
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minimum, samples.size * Short.SIZE_BYTES * 2),
                    )
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                if (held.get()) {
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    started = SystemClock.elapsedRealtime()
                    onStarted(started)
                    while (held.get()) {
                        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0)
                        if (!effective && held.get() && count > 0 && !silenced(recorder)) {
                            effective = (0 until count).any { abs(samples[it].toInt()) >= 128 }
                        }
                        if (count == 0) SystemClock.sleep(10)
                    }
                }
            }
        } catch (_: RuntimeException) {
            failed = true
        } finally {
            samples.fill(0)
            try {
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: RuntimeException) {
                failed = true
            } finally {
                try {
                    recorder?.release()
                } catch (_: RuntimeException) {
                    failed = true
                }
            }
        }
        return ProbeCaptureOutcome(started, SystemClock.elapsedRealtime(), effective, failed)
    }

    private fun silenced(recorder: AudioRecord): Boolean =
        Build.VERSION.SDK_INT >= 29 && recorder.activeRecordingConfiguration?.isClientSilenced != false

    private companion object {
        const val RATE = 16_000
    }
}
