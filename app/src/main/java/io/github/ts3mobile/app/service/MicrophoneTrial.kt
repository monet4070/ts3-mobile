package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneLevel
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.audio.opus.OpusMicrophoneCapture
import io.github.ts3mobile.protocol.sanitizedFailureTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class MicrophoneTrialPhase { IDLE, RUNNING, TIMED_OUT, UNAVAILABLE, FAILED }

internal data class MicrophoneTrialState(
    val phase: MicrophoneTrialPhase = MicrophoneTrialPhase.IDLE,
    val deviceType: Int? = null,
    val level: MicrophoneLevel? = null,
)

internal interface MicrophoneTrialSource : AutoCloseable {
    val isCapturing: Boolean
    val isSilenced: Boolean

    fun start(settings: MicrophoneSettings)

    fun reading(): MicrophoneTrialState
}

internal class LocalMicrophoneTrialSource(private val capture: OpusMicrophoneCapture) : MicrophoneTrialSource {
    private val levels = capture.observeLevels()
    override val isCapturing: Boolean get() = capture.isCapturing
    override val isSilenced: Boolean get() = capture.isInputSilenced

    override fun start(settings: MicrophoneSettings) {
        capture.configure(settings, volumeGate = true)
        capture.start()
    }

    override fun reading() = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING, capture.actualInputType, levels.latest)

    override fun close() {
        levels.close()
        capture.close()
    }
}

internal class MicrophoneTrial(
    private val scope: CoroutineScope,
    private val createSource: () -> MicrophoneTrialSource,
) {
    private val mutableState = MutableStateFlow(MicrophoneTrialState())
    val state = mutableState.asStateFlow()
    private var source: MicrophoneTrialSource? = null
    private var observer: Job? = null

    fun start(settings: MicrophoneSettings) {
        if (mutableState.value.phase == MicrophoneTrialPhase.RUNNING || !stop()) return
        try {
            val capture = createSource().also { source = it }
            capture.start(settings)
            mutableState.value = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING)
            observer =
                scope.launch {
                    repeat(TRIAL_DURATION_MS / SAMPLE_INTERVAL_MS) {
                        delay(SAMPLE_INTERVAL_MS.toLong())
                        try {
                            if (!capture.isCapturing || capture.isSilenced) {
                                stop(MicrophoneTrialPhase.UNAVAILABLE)
                                return@launch
                            }
                            mutableState.value = capture.reading()
                        } catch (error: Exception) {
                            fail(error)
                            return@launch
                        }
                    }
                    stop(MicrophoneTrialPhase.TIMED_OUT)
                }
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        System.err.println("TS3_AUDIO: local microphone test failed: ${error.sanitizedFailureTypes()}")
        stop(MicrophoneTrialPhase.FAILED)
    }

    fun stop(result: MicrophoneTrialPhase = MicrophoneTrialPhase.IDLE): Boolean {
        observer?.cancel()
        observer = null
        return try {
            source?.close()
            source = null
            mutableState.value = MicrophoneTrialState(result)
            true
        } catch (error: Exception) {
            System.err.println("TS3_AUDIO: local microphone test release failed: ${error.sanitizedFailureTypes()}")
            mutableState.value = MicrophoneTrialState(MicrophoneTrialPhase.FAILED)
            false
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 100
        const val TRIAL_DURATION_MS = 30_000
    }
}
