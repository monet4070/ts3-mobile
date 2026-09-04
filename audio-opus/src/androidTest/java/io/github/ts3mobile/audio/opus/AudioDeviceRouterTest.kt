package io.github.ts3mobile.audio.opus

import android.media.AudioAttributes
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
    fun repeatsTenRouteChangesAndRestoresAudioMode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val initialMode = audioManager.mode
        val router = AudioDeviceRouter(context) {}

        try {
            router.start()
            val initialState = router.state
            assertEquals(SYSTEM_AUDIO_ROUTE_ID, initialState.selectedRouteId)
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
                assertRouteSelected(router, audioManager, firstRoute)
                assertRouteSelected(router, audioManager, secondRoute)
            }
        } finally {
            router.close()
        }

        assertEquals(initialMode, audioManager.mode)
    }

    @Test
    fun playbackUsesTheVoiceCommunicationStrategy() {
        val attributes = voicePlaybackAudioAttributes()

        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, attributes.contentType)
    }

    private fun assertRouteSelected(
        router: AudioDeviceRouter,
        audioManager: AudioManager,
        route: AudioRouteOption,
    ) {
        router.selectRoute(route.id)
        assertEquals(route.id, router.state.selectedRouteId)
        assertEquals(null, router.state.error)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && route.id != SYSTEM_AUDIO_ROUTE_ID) {
            assertEquals(route.id, audioManager.communicationDevice?.id)
        }
    }

    private companion object {
        const val ROUTE_CHANGE_CYCLES = 5
    }
}
