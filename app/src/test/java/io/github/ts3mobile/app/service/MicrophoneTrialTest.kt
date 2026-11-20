package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneTrialTest {
    @Test fun timeoutClosesCaptureAndRepeatedStartDoesNotOpenAnotherRecorder() =
        runTest {
            val capture = SampleSource()
            var opened = 0
            val trial = MicrophoneTrial(this) { capture.also { opened++ } }
            val settings = MicrophoneSettings(volumePercent = 135, gateThresholdDb = -32)
            trial.start(settings)
            trial.start(MicrophoneSettings())
            assertEquals(1, opened)
            assertEquals(settings, capture.settings)
            advanceTimeBy(100L)
            runCurrent()
            assertNull(trial.state.value.deviceType)
            capture.device = 7
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(7, trial.state.value.deviceType)
            advanceTimeBy(29_800L)
            runCurrent()
            assertEquals(MicrophoneTrialPhase.TIMED_OUT, trial.state.value.phase)
            assertFalse(capture.isCapturing)
            assertEquals(1, capture.closes)
            assertNull(trial.state.value.deviceType)
        }

    @Test fun aSilencedInputIsReleasedAndAnExplicitRestartUsesANewSource() =
        runTest {
            val capture = SampleSource()
            val replacement = SampleSource()
            val sources = ArrayDeque(listOf(capture, replacement))
            val trial = MicrophoneTrial(this, sources::removeFirst)
            trial.start(MicrophoneSettings())
            capture.isSilenced = true
            advanceTimeBy(100L)
            runCurrent()
            assertEquals(MicrophoneTrialPhase.UNAVAILABLE, trial.state.value.phase)
            assertFalse(capture.isCapturing)
            assertFalse(replacement.isCapturing)
            trial.start(MicrophoneSettings())
            assertTrue(replacement.isCapturing)
            assertTrue(trial.stop())
            assertTrue(trial.stop())
            assertEquals(1, replacement.closes)
            assertEquals(MicrophoneTrialState(), trial.state.value)
        }

    @Test fun failedPermissionAndUnreleasedResourcesCannotStartInParallel() =
        runTest {
            val denied = SampleSource(startError = SecurityException("synthetic permission denial"))
            val retained = SampleSource(releaseFails = true)
            val remaining = ArrayDeque(listOf(denied, retained, SampleSource()))
            val trial = MicrophoneTrial(this, remaining::removeFirst)
            trial.start(MicrophoneSettings())
            assertEquals(MicrophoneTrialPhase.FAILED, trial.state.value.phase)
            assertEquals(1, denied.closes)
            trial.start(MicrophoneSettings())
            assertFalse(trial.stop())
            trial.start(MicrophoneSettings())
            assertEquals(1, remaining.size)
            retained.releaseFails = false
            assertTrue(trial.stop())
        }

    private class SampleSource(
        private val startError: Exception? = null,
        var releaseFails: Boolean = false,
    ) : MicrophoneTrialSource {
        override var isCapturing = false
        override var isSilenced = false
        var closes = 0
        var device: Int? = null
        var settings: MicrophoneSettings? = null

        override fun start(settings: MicrophoneSettings) {
            startError?.let { throw it }
            this.settings = settings
            isCapturing = true
        }

        override fun reading() = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING, device)

        override fun close() {
            closes++
            check(!releaseFails)
            isCapturing = false
        }
    }
}
