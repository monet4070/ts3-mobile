package io.github.ts3mobile.app.service

import android.media.AudioDeviceInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.audio.opus.EchoCancellationState
import io.github.ts3mobile.audio.opus.MicrophoneCapture
import io.github.ts3mobile.audio.opus.MicrophoneCaptureState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only a fake microphone is constructed: no AudioRecord, permission or network use. */
class MicrophoneControllerTest {
    @Test fun destructionRequestsStopImmediatelyAndKeepsCleanupAliveAfterScopeCancellation() {
        Fixture(Dispatchers.IO).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val retired = CountDownLatch(1)
            fixture.controller.setAppVisible(true)
            fixture.controller.setMode(MicrophoneMode.CONTINUOUS)
            assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
            fixture.capture.stopEntered = entered
            fixture.capture.stopRelease = release
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    fixture.controller.closeAfterRelease { retired.countDown() }
                }
                assertEquals(1, fixture.capture.stopRequests)
                fixture.scope.cancel()
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertEquals(1L, retired.count)
                assertTrue(fixture.foreground.last())
            } finally {
                release.countDown()
            }
            assertTrue(retired.await(3, TimeUnit.SECONDS))
            assertFalse(fixture.foreground.last())
            assertFalse(fixture.capture.isCapturing)
        }
    }

    @Test fun outputOnlyRouteChangeInvalidatesCaptureEvenWithUnchangedInputPreference() =
        Fixture().use { fixture ->
            fixture.controller.setPreferredDevice(null)
            fixture.controller.setPreferredDevice(null)
            fixture.controller.invalidateRouting()
            assertEquals(1, fixture.capture.routeInvalidations)
            assertEquals(0, fixture.capture.starts)
        }

    @Test fun offReturnsOnMainWhileStopBlocksAndQuickOnWaitsForOldCapture() {
        Fixture(Dispatchers.IO).use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                fixture.controller.setAppVisible(true)
                fixture.controller.setMode(MicrophoneMode.CONTINUOUS)
                assertTrue(fixture.capture.started.await(3, TimeUnit.SECONDS))
                fixture.capture.stopEntered = entered
                fixture.capture.stopRelease = release
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    fixture.controller.setMode(MicrophoneMode.OFF)
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertTrue(fixture.state.value.isCapturing)
                assertFalse(fixture.state.value.isTransmitting)
                assertTrue(fixture.foreground.last())
                fixture.controller.setMode(MicrophoneMode.CONTINUOUS)
                assertEquals(1, fixture.capture.starts)
            } finally {
                release.countDown()
            }
            assertTrue(fixture.capture.startedAgain.await(3, TimeUnit.SECONDS))
            runBlocking { fixture.controller.stopSerialized() }
        }
    }

    @Test fun gateSilenceKeepsCaptureAndForegroundTypeUntilExplicitOff() =
        Fixture().use { fixture ->
            fixture.controller.setAppVisible(true)
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            assertTrue(fixture.state.value.isCapturing)
            assertFalse(fixture.state.value.isTransmitting)
            assertTrue(fixture.foreground.last())
            fixture.capture.speech(true)
            assertTrue(fixture.state.value.isTransmitting)
            fixture.capture.speech(false)
            assertFalse(fixture.state.value.isTransmitting)
            assertTrue(fixture.state.value.isCapturing)
            assertTrue(fixture.foreground.last())
            fixture.controller.setMode(MicrophoneMode.OFF)
            assertFalse(fixture.capture.isCapturing)
            assertFalse(fixture.state.value.isCapturing)
            assertFalse(fixture.foreground.last())
        }

    @Test fun settingsDoNotActivateMicrophoneAndCanUpdateExistingBackgroundCapture() =
        Fixture().use { fixture ->
            fixture.controller.setSettings(MicrophoneSettings(volumePercent = 150, noiseSuppression = false))
            assertEquals(0, fixture.capture.starts)
            fixture.controller.setAppVisible(true)
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            fixture.controller.setAppVisible(false)
            fixture.controller.setSettings(fixture.state.value.microphoneSettings.copy(echoCancellation = false))
            fixture.controller.reconcile()
            assertEquals(1, fixture.capture.starts)
            assertEquals(150, fixture.capture.settings.volumePercent)
            assertFalse(fixture.capture.settings.noiseSuppression)
            assertFalse(fixture.capture.settings.echoCancellation)
            assertEquals(EchoCancellationState.DISABLED, fixture.state.value.echoCancellationState)
            assertTrue(fixture.state.value.isCapturing)
        }

    @Test fun stoppedBackgroundGateDefersUntilAppReturns() =
        Fixture().use { fixture ->
            fixture.controller.setAppVisible(true)
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            fixture.controller.setAppVisible(false)
            runBlocking { fixture.controller.stopSerialized() }
            fixture.controller.reconcile()
            assertEquals(1, fixture.capture.starts)
            assertEquals(UserMessage.MicrophoneResumeInApp, fixture.state.value.microphoneError)
            assertFalse(fixture.foreground.last())
            fixture.controller.setAppVisible(true)
            assertEquals(2, fixture.capture.starts)
            assertTrue(fixture.state.value.isCapturing)
        }

    @Test fun gateRequiresPermissionAndPushToTalkCannotActivateIt() =
        Fixture().use { fixture ->
            fixture.permission = false
            fixture.controller.setAppVisible(true)
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            assertEquals(MicrophoneMode.PUSH_TO_TALK, fixture.state.value.microphoneMode)
            assertEquals(0, fixture.capture.starts)
            fixture.permission = true
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            fixture.controller.setPushToTalkPressed(true)
            fixture.controller.setPushToTalkPressed(false)
            assertEquals(1, fixture.capture.starts)
            assertTrue(fixture.state.value.isCapturing)
        }

    @Test fun disconnectAndFailureClearCaptureStateAndMicrophoneType() =
        Fixture().use { fixture ->
            fixture.controller.setAppVisible(true)
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            fixture.capture.fail()
            assertFalse(fixture.state.value.isCapturing)
            assertTrue(fixture.state.value.microphoneError is UserMessage.MicrophoneFailed)
            assertFalse(fixture.foreground.last())
            fixture.controller.reconcile()
            fixture.state.value = fixture.state.value.copy(status = ConnectionStatus(ConnectionPhase.RECONNECTING))
            fixture.controller.reconcile()
            assertFalse(fixture.capture.isCapturing)
            assertFalse(fixture.foreground.last())
        }

    @Test fun offDuringForegroundPromotionCannotStartCapture() =
        Fixture().use { fixture ->
            fixture.controller.setAppVisible(true)
            fixture.onPromotion = { fixture.controller.setMode(MicrophoneMode.OFF) }
            fixture.controller.setMode(MicrophoneMode.VOLUME_GATE)
            assertEquals(0, fixture.capture.starts)
            assertFalse(fixture.state.value.isCapturing)
        }

    @Test fun permissionRefreshClearsOnlyPermissionErrorsAndDoesNotActivateIdlePushToTalk() =
        Fixture().use { fixture ->
            fixture.permission = false
            fixture.controller.reportPermissionDenied()
            fixture.controller.refreshPermission()
            assertEquals(UserMessage.MicrophonePermissionForVoice, fixture.state.value.microphoneError)
            fixture.permission = true
            fixture.controller.refreshPermission()
            assertEquals(null, fixture.state.value.microphoneError)
            assertEquals(0, fixture.capture.starts)
            fixture.state.value = fixture.state.value.copy(microphoneError = UserMessage.MicrophoneFailed("fixture"))
            fixture.controller.refreshPermission()
            assertTrue(fixture.state.value.microphoneError is UserMessage.MicrophoneFailed)
        }

    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = MutableStateFlow(TeamSpeakServiceState(status = ConnectionStatus(ConnectionPhase.CONNECTED)))
        val foreground = mutableListOf<Boolean>()
        var permission = true
        var onPromotion: () -> Unit = {}
        lateinit var capture: FakeCapture
        val controller =
            MicrophoneController(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                scope = scope,
                state = state,
                diagnosticsRecorder = DiagnosticsRecorder(),
                refreshDiagnostics = {},
                updateForegroundType = {
                    foreground += it
                    if (it) onPromotion()
                },
                updateNotification = {},
                conciseMessage = { "capture failure" },
                captureFactory = { _, failure, changed -> FakeCapture(failure, changed).also { capture = it } },
                permissionGranted = { permission },
                captureDispatcher = dispatcher,
            )

        override fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private class FakeCapture(
        private val onFailure: (Throwable) -> Unit,
        private val onState: (MicrophoneCaptureState) -> Unit,
    ) : MicrophoneCapture {
        override var isCapturing = false
        override var generation = 0L
        override var isTransmitting = false
        var starts = 0
        var routeInvalidations = 0
        var stopRequests = 0
        val started = CountDownLatch(1)
        val startedAgain = CountDownLatch(1)
        var stopEntered: CountDownLatch? = null
        var stopRelease: CountDownLatch? = null
        var settings = MicrophoneSettings()
        private var gated = false

        override fun configure(
            settings: MicrophoneSettings,
            volumeGate: Boolean,
        ) {
            this.settings = settings
            gated = volumeGate
            if (isCapturing) speech(!gated)
        }

        override fun setPreferredDevice(device: AudioDeviceInfo?) = Unit

        override fun requestStop() {
            stopRequests++
        }

        override fun invalidateRouting() {
            routeInvalidations++
        }

        override fun start() {
            starts++
            generation++
            isCapturing = true
            speech(!gated)
            started.countDown()
            if (starts == 2) startedAgain.countDown()
        }

        override fun stop() {
            stopEntered?.countDown()
            stopRelease?.let { check(it.await(5, TimeUnit.SECONDS)) }
            isCapturing = false
            isTransmitting = false
            onState(MicrophoneCaptureState())
        }

        fun speech(active: Boolean) {
            isTransmitting = active
            onState(
                MicrophoneCaptureState(
                    isCapturing,
                    active,
                    if (settings.echoCancellation) EchoCancellationState.ENABLED else EchoCancellationState.DISABLED,
                ),
            )
        }

        fun fail() = onFailure(IllegalStateException("fixture"))

        override fun close() = stop()

        override fun isReady() = false

        override fun pollEncodedFrame(): ByteArray? = null
    }
}
