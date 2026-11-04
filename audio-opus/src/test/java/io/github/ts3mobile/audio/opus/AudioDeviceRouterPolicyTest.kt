package io.github.ts3mobile.audio.opus

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDeviceRouterPolicyTest {
    private val speaker = AudioRouteOption(1, AudioRouteKind.SPEAKER)
    private val bluetooth = AudioRouteOption(2, AudioRouteKind.BLUETOOTH)
    private val secondBluetooth = AudioRouteOption(3, AudioRouteKind.BLUETOOTH)

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

    @Test fun silentCommunicationFallbackMutesEvenWhileBluetoothIsStillAvailable() {
        val platform = Platform(listOf(speaker, bluetooth))
        AudioDeviceRouter(platform) {}.use { router ->
            router.start()
            platform.lost()
            platform.changed()
            assertTrue(router.state.routes.contains(bluetooth))
            assertTrue(router.state.safetyMuted)
            assertEquals(AudioRoutingErrorKind.BLUETOOTH_ROUTE_LOST, router.state.error?.kind)
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
            platform.lost()
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
        platform.lost()
        platform.changed()
        assertEquals(AudioRoutingState.Default, router.state)
        router.start()
        assertEquals(bluetooth.id, router.state.selectedRouteId)
        router.close()
    }

    private class Platform(var available: List<AudioRouteOption>) : AudioRoutePlatform {
        var changed: () -> Unit = {}
        var lost: () -> Unit = {}
        var beforeSelect: () -> Unit = {}
        var accept = true
        var failure: RuntimeException? = null
        val selections = mutableListOf<Int>()

        override fun routes() = listOf(AudioRoutingState.SystemRoute) + available

        override fun start(
            onDevicesChanged: () -> Unit,
            onBluetoothLost: () -> Unit,
        ) {
            changed = onDevicesChanged
            lost = onBluetoothLost
        }

        override fun select(routeId: Int): Boolean {
            beforeSelect()
            selections += routeId
            failure?.let { throw it }
            return accept
        }

        override fun preferredOutput(routeId: Int): AudioDeviceInfo? = null

        override fun preferredInput(routeId: Int): AudioDeviceInfo? = null

        override fun stop() = Unit

        fun change(routes: List<AudioRouteOption>) {
            available = routes
            changed()
        }
    }
}
