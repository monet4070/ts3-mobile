package io.github.ts3mobile.app.service

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.audio.opus.ReceiveDelayProfile
import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackPreferenceTest {
    @Test fun savingAProfileAppliesItInTheSameConnectedSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = (instrumentation.targetContext.applicationContext as Ts3MobileApplication).playbackPreferences
        instrumentation.withOfflineConnectedSession { service, binder ->
            runBlocking {
                val initial = withTimeout(5_000) { checkNotNull(preferences.state.first { !it.busy }.profile) }
                val next = if (initial == ReceiveDelayProfile.BALANCED) ReceiveDelayProfile.STABILITY else ReceiveDelayProfile.BALANCED
                try {
                    binder.redactedDiagnosticsJson()
                    service.sessionMutex.withLock { service.startPlayback() }
                    val original = binder.lastProfileEvent()
                    assertEquals(initial.code(), original.getInt("value"))
                    val connection = service.mutableState.value.bookmarkConnection

                    instrumentation.runOnMainSync { preferences.select(next) }
                    withTimeout(5_000) {
                        while (binder.lastProfileEvent().getInt("value") != next.code()) delay(20)
                    }

                    assertEquals(original.getLong("session"), binder.lastProfileEvent().getLong("session"))
                    assertEquals(ConnectionPhase.CONNECTED, service.mutableState.value.status.phase)
                    assertEquals(connection, service.mutableState.value.bookmarkConnection)
                } finally {
                    withTimeout(5_000) { preferences.state.first { !it.busy } }
                    instrumentation.runOnMainSync { preferences.select(initial) }
                    withTimeout(5_000) { preferences.state.first { !it.busy && it.profile == initial } }
                }
            }
        }
    }

    private suspend fun TeamSpeakService.SessionBinder.lastProfileEvent(): JSONObject {
        val events = JSONObject(redactedDiagnosticsJson()).getJSONObject("audio").getJSONArray("events")
        return (0 until events.length()).map(events::getJSONObject).last { it.getString("kind") == "RECEIVE_DELAY_PROFILE" }
    }

    private fun ReceiveDelayProfile.code(): Int = if (this == ReceiveDelayProfile.BALANCED) 0 else 1
}
