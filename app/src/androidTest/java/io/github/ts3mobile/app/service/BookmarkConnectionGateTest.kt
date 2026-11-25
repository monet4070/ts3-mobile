package io.github.ts3mobile.app.service

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkConnectionGateTest {
    @Test fun guardedBookmarkIntentDoesNotReplaceAnAlreadyConnectedSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.withOfflineConnectedSession { service, binder ->
            val original = binder.state.value.bookmarkConnection
            instrumentation.runOnMainSync {
                val intent =
                    TeamSpeakService.connectionIntent(
                        instrumentation.targetContext,
                        ServerConfig("other.example.invalid", nickname = "Fixture"),
                        requireIdle = true,
                    )
                service.onStartCommand(intent, 0, 1)
                assertEquals(ConnectionPhase.CONNECTED, binder.state.value.status.phase)
                assertEquals(original, binder.state.value.bookmarkConnection)
            }
        }
    }
}
