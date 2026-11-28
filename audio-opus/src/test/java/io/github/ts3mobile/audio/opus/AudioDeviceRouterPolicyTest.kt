package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceRouterPolicyTest {
    private val speaker = AudioRouteOption(1, AudioRouteKind.SPEAKER)
    private val bluetooth = AudioRouteOption(2, AudioRouteKind.BLUETOOTH)
    private val secondBluetooth = AudioRouteOption(3, AudioRouteKind.BLUETOOTH)
    private val bluetoothCall = AudioRouteOption(5, AudioRouteKind.BLUETOOTH, bluetoothCall = true)

    @Test fun callRouteIsNeverSelectedAutomatically() {
        val platform = Platform(listOf(speaker, bluetoothCall))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, router.state.selectedRouteId)
            platform.change(listOf(speaker, bluetoothCall, bluetooth))
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertTrue(platform.callRequests.isEmpty())
        }
    }

    @Test fun callRouteStagesOfflineAndReleasesOnMediaSelectionOrSessionStop() {
        val microphone = AudioInputOption(12, AudioInputKind.USB)
        val platform = Platform(listOf(bluetooth, bluetoothCall)).apply { inputs = listOf(microphone) }
        AudioDeviceRouter(platform) {}.use { router ->
            router.watchDevices()
            router.selectInput(microphone.id)
            router.selectRoute(bluetoothCall.id)
            assertTrue(platform.callRequests.isEmpty())
            assertFalse(router.isBluetoothCallActive())
            router.start()
            assertEquals(listOf(bluetoothCall.id), platform.callRequests)
            assertTrue(router.isBluetoothCallActive())
            router.preferredInputDevice()
            assertTrue(platform.selectedInputs.isEmpty())
            router.selectInput(SYSTEM_AUDIO_ROUTE_ID)
            platform.change(listOf(bluetooth, secondBluetooth, bluetoothCall))
            assertEquals(bluetoothCall.id, router.state.selectedRouteId)
            router.selectRoute(bluetooth.id)
            assertFalse(platform.callActive)
            router.preferredInputDevice()
            assertEquals(listOf(microphone.id), platform.selectedInputs)
            router.selectRoute(bluetoothCall.id)
            router.stop()
            assertFalse(platform.callActive)
            router.start()
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertEquals(2, platform.callRequests.size)
        }
    }

    @Test fun rejectedCallRouteReleasesTheRequestAndKeepsMediaSelected() {
        val platform = Platform(listOf(bluetooth, bluetoothCall))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.accept = false
            router.selectRoute(bluetoothCall.id)
            assertFalse(platform.callActive)
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertEquals(AudioRoutingErrorKind.SWITCH_DENIED, router.state.error?.kind)
            assertTrue(router.state.safetyMuted)
        }
    }

    @Test fun removingCallDeviceMutesBeforeReturningToMedia() {
        val platform = Platform(listOf(bluetooth, bluetoothCall))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.selectRoute(bluetoothCall.id)
            platform.beforeSelect = { assertTrue(router.state.safetyMuted) }
            platform.change(listOf(bluetooth))
            assertFalse(platform.callActive)
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertTrue(router.state.safetyMuted)
        }
    }

    @Test fun unconfirmedCallOutputReleasesTheCallEvenWhenTheHeadsetRemainsAvailable() {
        val platform = Platform(listOf(bluetooth, bluetoothCall))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.selectRoute(bluetoothCall.id)
            router.onBluetoothRouteLost()
            assertFalse(platform.callActive)
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertEquals(AudioRoutingErrorKind.BLUETOOTH_ROUTE_LOST, router.state.error?.kind)
            assertTrue(router.state.safetyMuted)
        }
    }

    @Test fun selectsAlreadyConnectedBluetoothOnStart() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertFalse(router.state.safetyMuted)
            assertEquals(listOf(bluetooth.id), platform.selections)
        }
    }

    @Test fun hotPlugSelectsBluetoothButUnrelatedCallbacksRespectAManualSpeakerSelection() {
        val platform = Platform(listOf(speaker))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.change(listOf(speaker, bluetooth))
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            router.selectRoute(speaker.id)
            platform.changed()
            assertEquals(speaker.id, router.state.selectedRouteId)
            platform.change(listOf(speaker, bluetooth, secondBluetooth))
            assertEquals(secondBluetooth.id, router.state.selectedRouteId)
        }
    }

    @Test fun addingAnotherBluetoothDeviceDoesNotDisruptAnExistingBluetoothRoute() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.change(listOf(speaker, bluetooth, secondBluetooth))
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertEquals(listOf(bluetooth.id), platform.selections)
        }
    }

    @Test fun removalMutesBeforePlatformFallbackAndReconnectionCannotUnmute() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.beforeSelect = { assertTrue(router.state.safetyMuted) }
            platform.change(listOf(speaker))
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, router.state.selectedRouteId)
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.DEVICE_DISCONNECTED, router.state.error?.kind)
            platform.change(listOf(speaker, bluetooth))
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertTrue(router.state.safetyMuted)
            platform.beforeSelect = {}
            router.acknowledgeSafetyMute()
            assertFalse(router.state.safetyMuted)
        }
    }

    @Test fun actualTrackFallbackMutesEvenWhileBluetoothIsStillAvailable() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.onBluetoothRouteLost()
            platform.changed()
            assertTrue(router.state.routes.contains(bluetooth))
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.BLUETOOTH_ROUTE_LOST, router.state.error?.kind)
        }
    }

    @Test fun noisyBroadcastWhileIdleDoesNotResetAnAvailableMediaRoute() {
        val platform = Platform(listOf(speaker, bluetooth))
        val changes = mutableListOf<AudioRoutingState>()
        AudioDeviceRouter(platform, changes::add).use { router ->
            router.start()
            changes.clear()
            platform.noisy()
            router.prepareForPlayback()
            assertFalse(router.state.safetyMuted)
            assertEquals(null, router.state.error)
            assertTrue(changes.isEmpty())
            assertEquals(listOf(bluetooth.id, bluetooth.id), platform.selections)
        }
    }

    @Test fun noisyBroadcastWhilePlayingOnBluetoothLatchesMute() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.setPlaybackActive(true)
            platform.noisy()
            router.prepareForPlayback()
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.BLUETOOTH_ROUTE_LOST, router.state.error?.kind)
            assertEquals(listOf(bluetooth.id), platform.selections)
        }
    }

    @Test fun playbackFailsClosedIfSelectedHeadsetDisappearsBeforeDeviceCallback() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.available = listOf(speaker)
            router.prepareForPlayback()
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.DEVICE_DISCONNECTED, router.state.error?.kind)
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, router.state.selectedRouteId)
        }
    }

    @Test fun noisyBroadcastDoesNotOverrideExplicitSpeakerSelection() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.selectRoute(speaker.id)
            router.setPlaybackActive(true)
            platform.noisy()
            assertEquals(speaker.id, router.state.selectedRouteId)
            assertEquals(listOf(bluetooth.id, speaker.id), platform.selections)
            assertFalse(router.state.safetyMuted)
        }
    }

    @Test fun selectingAStaleDisconnectedDeviceMutesBeforeItsDelayedRemovalCallback() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.available = listOf(speaker)
            router.selectRoute(bluetooth.id)
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.DEVICE_UNAVAILABLE, router.state.error?.kind)
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, router.state.selectedRouteId)
        }
    }

    @Test fun switchIsBlockedBeforeThePlatformChangesAndDenialCannotEnableSpeakerFallback() {
        val platform = Platform(listOf(speaker))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.accept = false
            platform.beforeSelect = {
                assertTrue(router.state.switching)
                assertEquals(AudioRouteKind.BLUETOOTH, router.state.selectedRoute.kind)
            }
            platform.change(listOf(speaker, bluetooth))
            assertFalse(router.state.switching)
            assertTrue(router.state.safetyMuted)
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertEquals(AudioRoutingErrorKind.SWITCH_DENIED, router.state.error?.kind)
        }
    }

    @Test fun platformExceptionIsReportedWithoutLeakingItsMessage() {
        val platform = Platform(listOf(bluetooth)).apply { failure = SecurityException("private platform details") }
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.SWITCH_FAILED, router.state.error?.kind)
            assertFalse(router.state.error?.cause.orEmpty().contains("private"))
        }
    }

    @Test fun manualUnmuteRetriesBluetoothAndARefusedRetryKeepsTheSafetyMute() {
        val platform = Platform(listOf(bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.onBluetoothRouteLost()
            platform.accept = false
            router.acknowledgeSafetyMute()
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.SWITCH_DENIED, router.state.error?.kind)
            platform.accept = true
            router.acknowledgeSafetyMute()
            assertFalse(router.state.safetyMuted)
            assertEquals(listOf(bluetooth.id, bluetooth.id, bluetooth.id), platform.selections)
        }
    }

    @Test fun callbacksAfterStopCannotChangeStateAndNextStartSelectsBluetoothAgain() {
        val platform = Platform(listOf(bluetooth))
        val router = AudioDeviceRouter(platform) {}
        router.start()
        router.stop()
        router.onBluetoothRouteLost()
        platform.noisy()
        platform.changed()
        assertEquals(AudioRoutingState.Default, router.state)
        router.start()
        assertEquals(bluetooth.id, router.state.selectedRouteId)
        router.close()
    }

    @Test fun devicesCanBeSelectedBeforeStartingAConnection() {
        val microphone = AudioInputOption(11, AudioInputKind.LE_AUDIO)
        val platform = Platform(listOf(speaker, bluetooth)).apply { inputs = listOf(microphone) }
        AudioDeviceRouter(platform) {}.use { router ->
            router.watchDevices()
            assertTrue(router.state.routes.contains(bluetooth))
            assertTrue(router.state.input.routes.contains(microphone))
            router.selectRoute(speaker.id)
            router.selectInput(microphone.id)
            router.preferredInputDevice()
            assertEquals(listOf(microphone.id), platform.selectedInputs)
            assertTrue(platform.selections.isEmpty())
            router.start()
            assertEquals(speaker.id, router.state.selectedRouteId)
            assertEquals(microphone.id, router.state.input.selectedId)
            assertEquals(listOf(speaker.id), platform.selections)
            router.stop()
            assertTrue(router.state.routes.contains(bluetooth))
            assertEquals(microphone.id, router.state.input.selectedId)
            assertFalse(router.state.safetyMuted)
        }
    }

    @Test fun unpluggingOnlyTheInputFallsBackWithoutChangingPlayback() {
        val microphone = AudioInputOption(12, AudioInputKind.USB)
        val platform = Platform(listOf(bluetooth)).apply { inputs = listOf(microphone) }
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.selectInput(microphone.id)
            platform.inputs = emptyList()
            platform.changed()
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, router.state.input.selectedId)
            assertEquals(AudioRoutingErrorKind.DEVICE_DISCONNECTED, router.state.input.error)
            assertEquals(bluetooth.id, router.state.selectedRouteId)
            assertFalse(router.state.safetyMuted)
            router.selectInput(microphone.id)
            assertEquals(AudioRoutingErrorKind.DEVICE_UNAVAILABLE, router.state.input.error)
            router.selectInput(SYSTEM_AUDIO_ROUTE_ID)
            assertNull(router.state.input.error)
        }
    }

    @Test fun simultaneousBluetoothRemovalStillMutesBeforeSwitchingOutput() {
        val microphone = AudioInputOption(13, AudioInputKind.LE_AUDIO)
        val platform = Platform(listOf(bluetooth)).apply { inputs = listOf(microphone) }
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            router.selectInput(microphone.id)
            platform.beforeSelect = { assertTrue(router.state.safetyMuted) }
            platform.inputs = emptyList()
            platform.change(listOf(speaker))
            assertTrue(router.state.safetyMuted)
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, router.state.input.selectedId)
            assertEquals(AudioRoutingErrorKind.DEVICE_DISCONNECTED, router.state.input.error)
        }
    }

    @Test fun communicationOnlyAndVirtualSourcesAreNotExposedAsMediaChoices() {
        assertEquals(AudioInputKind.LE_AUDIO, AudioRouteDevices.inputKind(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioInputKind.BUILTIN, AudioRouteDevices.inputKind(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(AudioInputKind.USB, AudioRouteDevices.inputKind(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertNull(AudioRouteDevices.inputKind(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertNull(AudioRouteDevices.inputKind(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
        assertFalse(AudioRouteDevices.isSelectableMediaOutput(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioRouteDevices.isSelectableMediaOutput(AudioDeviceInfo.TYPE_HDMI))
    }

    private class Platform(var available: List<AudioRouteOption>) : AudioRoutePlatform {
        var inputs = emptyList<AudioInputOption>()
        var changed: () -> Unit = {}
        var noisy: () -> Unit = {}
        var beforeSelect: () -> Unit = {}
        var accept = true
        var failure: RuntimeException? = null
        var callActive = false
        val selections = mutableListOf<Int>()
        val selectedInputs = mutableListOf<Int>()
        val callRequests = mutableListOf<Int>()

        override fun routes() = listOf(AudioRoutingState.SystemRoute) + available

        override fun inputRoutes() = listOf(AudioInputRouting.Automatic) + inputs

        override fun start(
            onDevicesChanged: () -> Unit,
            onBecomingNoisy: () -> Unit,
        ) {
            changed = onDevicesChanged
            noisy = onBecomingNoisy
        }

        override fun isAvailable(routeId: Int): Boolean {
            beforeSelect()
            selections += routeId
            failure?.let { throw it }
            return accept && routes().any { it.id == routeId }
        }

        override fun preferredOutput(routeId: Int): AudioDeviceInfo? = null

        override fun selectBluetoothCall(routeId: Int): Boolean {
            callActive = true
            callRequests += routeId
            return accept
        }

        override fun clearBluetoothCall() {
            callActive = false
        }

        override fun preferredInput(routeId: Int): AudioDeviceInfo? = null

        override fun inputDevice(id: Int): AudioDeviceInfo? {
            selectedInputs += id
            return null
        }

        override fun stop() = Unit

        fun change(routes: List<AudioRouteOption>) {
            available = routes
            changed()
        }
    }
}
