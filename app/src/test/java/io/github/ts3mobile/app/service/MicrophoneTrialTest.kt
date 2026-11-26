package io.github.ts3mobile.app.service

import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun asyncDispatcherOffloadsStartAndCloseAndStopAndAwaitTracksRelease() =
        runBlocking {
            val started = CountDownLatch(1)
            val releaseGate = CountDownLatch(1)
            val requestStops = AtomicInteger(0)
            val closes = AtomicInteger(0)
            val source =
                object : MicrophoneTrialSource {
                    override var isCapturing = false
                    override val isSilenced = false

                    override fun start(settings: MicrophoneSettings) {
                        isCapturing = true
                        started.countDown()
                    }

                    override fun requestStop() {
                        requestStops.incrementAndGet()
                    }

                    override fun reading() = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING)

                    override fun close() {
                        check(releaseGate.await(5, TimeUnit.SECONDS))
                        closes.incrementAndGet()
                        isCapturing = false
                    }
                }
            val trial = MicrophoneTrial(this, Dispatchers.IO) { source }
            trial.start(MicrophoneSettings())
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { trial.state.first { it.phase == MicrophoneTrialPhase.RUNNING } }
            assertFalse(trial.isInputReleased)

            // Non-blocking stop triggers requestStop immediately and keeps isInputReleased false until close() completes.
            assertTrue(trial.stop())
            assertTrue(requestStops.get() >= 1)
            assertFalse(trial.isInputReleased)
            assertEquals(0, closes.get())

            releaseGate.countDown()
            assertTrue(withTimeout(3_000) { trial.stopAndAwait() })
            assertTrue(trial.isInputReleased)
            assertEquals(1, closes.get())
            assertEquals(MicrophoneTrialPhase.IDLE, trial.state.value.phase)
        }

    @Test fun cancellingAsyncStartBeforeDispatcherRunsReleasesInputAndAllowsRestart() =
        runTest {
            val captureDispatcher = StandardTestDispatcher(testScheduler)
            var createdCount = 0
            val trial =
                MicrophoneTrial(backgroundScope, captureDispatcher) {
                    createdCount++
                    SampleSource()
                }

            trial.start(MicrophoneSettings())
            assertEquals(0, createdCount)
            assertFalse(trial.isInputReleased)

            assertTrue(trial.stop())
            assertEquals(0, createdCount)
            assertTrue("isInputReleased remained stuck false after cancelling unstarted trial", trial.isInputReleased)
            assertEquals(MicrophoneTrialPhase.IDLE, trial.state.value.phase)
            assertTrue(trial.stopAndAwait())

            trial.start(MicrophoneSettings())
            runCurrent()
            assertEquals(1, createdCount)
            assertEquals(MicrophoneTrialPhase.RUNNING, trial.state.value.phase)
            assertTrue(trial.stopAndAwait())
            assertTrue(trial.isInputReleased)
        }

    @Test fun concurrentStopsCloseSourceOnceReturnToIdleAndAllowRestart() =
        runBlocking {
            repeat(20) { iteration ->
                val sources = CopyOnWriteArrayList<AtomicSampleSource>()
                var currentStarted = CountDownLatch(1)
                var currentCloseGate: CompletableDeferred<Unit>? = null
                val trial =
                    MicrophoneTrial(this, Dispatchers.IO) {
                        val source = AtomicSampleSource(startedLatch = currentStarted, closeGate = currentCloseGate)
                        sources += source
                        source
                    }

                trial.start(MicrophoneSettings())
                assertTrue("iteration $iteration did not start", currentStarted.await(3, TimeUnit.SECONDS))
                withTimeout(3_000) { trial.state.first { it.phase == MicrophoneTrialPhase.RUNNING } }
                val activeSource = sources.single()
                assertEquals(MicrophoneTrialPhase.RUNNING, trial.state.value.phase)

                val barrier = CountDownLatch(2)
                val firstStop =
                    async(Dispatchers.Default) {
                        barrier.countDown()
                        assertTrue(barrier.await(3, TimeUnit.SECONDS))
                        trial.stop()
                    }
                val secondStop =
                    async(Dispatchers.IO) {
                        barrier.countDown()
                        assertTrue(barrier.await(3, TimeUnit.SECONDS))
                        trial.stopAndAwait()
                    }

                assertTrue(withTimeout(3_000) { firstStop.await() })
                assertTrue(withTimeout(3_000) { secondStop.await() })
                assertEquals("iteration $iteration closed source != 1", 1, activeSource.closes.get())
                assertEquals(MicrophoneTrialPhase.IDLE, trial.state.value.phase)
                assertTrue(trial.isInputReleased)

                currentStarted = CountDownLatch(1)
                currentCloseGate = null
                trial.start(MicrophoneSettings())
                assertTrue("iteration $iteration failed to restart", currentStarted.await(3, TimeUnit.SECONDS))
                withTimeout(3_000) { trial.state.first { it.phase == MicrophoneTrialPhase.RUNNING } }
                assertEquals(2, sources.size)
                assertEquals(MicrophoneTrialPhase.RUNNING, trial.state.value.phase)
                assertTrue(withTimeout(3_000) { trial.stopAndAwait() })
                assertEquals(1, sources.last().closes.get())
                assertTrue(trial.isInputReleased)
            }
        }

    @Test fun concurrentStopsDuringInFlightStartCloseCreatedSourceOnceAndReturnToIdle() =
        runBlocking {
            val createEntered = CountDownLatch(1)
            val allowCreate = CountDownLatch(1)
            val sources = CopyOnWriteArrayList<AtomicSampleSource>()
            val trial =
                MicrophoneTrial(this, Dispatchers.IO) {
                    if (sources.isEmpty()) {
                        createEntered.countDown()
                        check(allowCreate.await(5, TimeUnit.SECONDS))
                    }
                    AtomicSampleSource().also { sources += it }
                }

            trial.start(MicrophoneSettings())
            assertTrue(createEntered.await(3, TimeUnit.SECONDS))

            assertTrue(trial.stop())
            val secondStop = async(Dispatchers.Default) { trial.stopAndAwait() }
            allowCreate.countDown()

            assertTrue(withTimeout(3_000) { secondStop.await() })
            assertEquals(1, sources.size)
            assertEquals(1, sources.single().closes.get())
            assertEquals(MicrophoneTrialPhase.IDLE, trial.state.value.phase)
            assertTrue(trial.isInputReleased)

            trial.start(MicrophoneSettings())
            val restarted =
                withTimeout(3_000) {
                    while (sources.size < 2 || !sources.last().isCapturing) {
                        kotlinx.coroutines.delay(5)
                    }
                    true
                }
            assertTrue(restarted)
            withTimeout(3_000) { trial.state.first { it.phase == MicrophoneTrialPhase.RUNNING } }
            assertEquals(MicrophoneTrialPhase.RUNNING, trial.state.value.phase)
            assertTrue(withTimeout(3_000) { trial.stopAndAwait() })
            assertEquals(1, sources.last().closes.get())
        }

    private class AtomicSampleSource(
        private val startedLatch: CountDownLatch? = null,
        private val closeGate: CompletableDeferred<Unit>? = null,
    ) : MicrophoneTrialSource {
        @Volatile override var isCapturing = false
        override val isSilenced = false
        val closes = AtomicInteger(0)

        override fun start(settings: MicrophoneSettings) {
            isCapturing = true
            startedLatch?.countDown()
        }

        override fun reading() = MicrophoneTrialState(MicrophoneTrialPhase.RUNNING)

        override fun close() {
            closes.incrementAndGet()
            isCapturing = false
            closeGate?.let { runBlocking { it.await() } }
        }
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
