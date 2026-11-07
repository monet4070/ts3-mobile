package io.github.ts3mobile.app.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real foreground-service entry is rejected before any identity read, socket or capture. */
class IdentityMaintenanceServiceTest {
    @Test fun maintenanceRejectsForegroundConnectAndStopsTheStartWithoutPostingConnecting() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val identities = (context.applicationContext as Ts3MobileApplication).identities
            val token = checkNotNull(identities.gate.beginMaintenance())
            val connected = CompletableDeferred<TeamSpeakService.SessionBinder>()
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?,
                    ) {
                        connected.complete(service as TeamSpeakService.SessionBinder)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
            var bound = false
            try {
                bound = context.bindService(Intent(context, TeamSpeakService::class.java), connection, Context.BIND_AUTO_CREATE)
                assertTrue(bound)
                val binder = withTimeout(5_000) { connected.await() }
                TeamSpeakService.connect(context, ServerConfig("fixture.invalid", 9987, "Fixture"))
                val state = withTimeout(5_000) { binder.state.first { it.statusMessage == UserMessage.IdentityMaintenance } }
                assertEquals(ConnectionPhase.DISCONNECTED, state.status.phase)
                assertNull(state.serverLabel)
                assertFalse(state.identityReady)
                assertFalse(state.isCapturing)
                assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 4103 })
                // Beyond Android's foreground promotion deadline: stopped start must not crash the process.
                delay(12_000)
                assertEquals(ConnectionPhase.DISCONNECTED, binder.state.value.status.phase)
            } finally {
                identities.gate.finishMaintenance(token)
                if (bound) context.unbindService(connection)
            }
        }
}
