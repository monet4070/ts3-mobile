package io.github.ts3mobile.app.service

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun Instrumentation.withOfflineConnectedSession(test: (TeamSpeakService, TeamSpeakService.SessionBinder) -> Unit) {
    val ready = CountDownLatch(1)
    var bound: TeamSpeakService.SessionBinder? = null
    val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                bound = binder as TeamSpeakService.SessionBinder
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
    val context = targetContext
    val intent = Intent(context, TeamSpeakService::class.java)
    val notifications = context.getSystemService(NotificationManager::class.java)
    notifications.cancelFixtureNotification(SessionNotifications.NOTIFICATION_ID)
    assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
    var fixtureService: TeamSpeakService? = null
    try {
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val binder = checkNotNull(bound)
        val owner = binder.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
        val service = owner.get(binder) as TeamSpeakService
        fixtureService = service
        runOnMainSync {
            service.sessionGeneration.beginSession()
            service.mutableState.value =
                TeamSpeakServiceState(
                    status = ConnectionStatus(ConnectionPhase.CONNECTED),
                    serverLabel = "fixture.example.invalid",
                    bookmarkConnection = BookmarkConnection("fixture-token", "fixture.example.invalid", 9987, "Fixture"),
                )
            service.updateNotification()
        }
        test(service, binder)
    } finally {
        try {
            runOnMainSync { fixtureService?.removeForegroundNotification() }
        } finally {
            context.unbindService(connection)
            context.stopService(intent)
            waitForIdleSync()
            notifications.cancelFixtureNotification(SessionNotifications.NOTIFICATION_ID)
        }
    }
}
