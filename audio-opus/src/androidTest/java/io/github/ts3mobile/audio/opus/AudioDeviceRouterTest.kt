package io.github.ts3mobile.audio.opus

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDeviceRouterTest {
    @Test
    fun startingSelectingAndStoppingNeverChangeSystemCommunicationMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val initialMode = audioManager.mode
        val initialCommunicationDevice = communicationDeviceId(audioManager)
        val router = AudioDeviceRouter(context) {}

        try {
            router.start()
            assertEquals(initialMode, audioManager.mode)
            assertEquals(initialCommunicationDevice, communicationDeviceId(audioManager))
            val initialState = router.state
            val expectedRoute = initialState.routes.firstOrNull { it.kind == AudioRouteKind.BLUETOOTH }?.id ?: SYSTEM_AUDIO_ROUTE_ID
            assertEquals(expectedRoute, initialState.selectedRouteId)
            assertTrue(initialState.routes.any { it.id == SYSTEM_AUDIO_ROUTE_ID })

            val firstRoute =
                initialState.routes.firstOrNull {
                    it.kind == AudioRouteKind.SPEAKER
                } ?: initialState.routes.first { it.id != SYSTEM_AUDIO_ROUTE_ID }
            val secondRoute =
                initialState.routes.firstOrNull {
                    it.id != SYSTEM_AUDIO_ROUTE_ID && it.id != firstRoute.id
                } ?: AudioRoutingState.SystemRoute

            repeat(ROUTE_CHANGE_CYCLES) {
                assertRouteSelected(router, firstRoute)
                assertRouteSelected(router, secondRoute)
                assertEquals(initialMode, audioManager.mode)
                assertEquals(initialCommunicationDevice, communicationDeviceId(audioManager))
            }
        } finally {
            router.close()
        }

        assertEquals(initialMode, audioManager.mode)
        assertEquals(initialCommunicationDevice, communicationDeviceId(audioManager))
    }

    @Test
    fun playbackUsesMediaVolumeWithPrivateSpeechAttributes() {
        val attributes = voicePlaybackAudioAttributes()

        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(AudioAttributes.ALLOW_CAPTURE_BY_NONE, attributes.allowedCapturePolicy)
        }
    }

    private fun assertRouteSelected(
        router: AudioDeviceRouter,
        route: AudioRouteOption,
    ) {
        router.selectRoute(route.id)
        assertEquals(route.id, router.state.selectedRouteId)
        assertEquals(null, router.state.error)
        assertEquals(route.id.takeUnless { it == SYSTEM_AUDIO_ROUTE_ID }, router.preferredOutputDevice()?.id)
        if (route.kind == AudioRouteKind.BLUETOOTH) {
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, router.preferredInputDevice()?.type)
        }
    }

    private fun communicationDeviceId(audioManager: AudioManager): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice?.id else null

    private companion object {
        const val ROUTE_CHANGE_CYCLES = 5
    }
}
