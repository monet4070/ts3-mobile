package io.github.ts3mobile.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PokeNotificationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Test fun backgroundPokePostsGenericNotificationThenForegroundClearsIt() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val needsPermission =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val notifications = PokeNotifications(context)
        notifications.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store =
            ChatStore().apply {
                begin(1)
                bind(
                    1,
                    1,
                    ChatBinding(1, 1, 1, 1),
                    participants = listOf(Ts3Participant(2, 1, "Private sender", false, false, false, "fixture-uid")),
                )
            }
        val observer = PokeAlertObserver(scope, store.state, notifications::show, notifications::clear)
        try {
            store.receivePoke(1, 1, Ts3PokeEvent(2, "fixture-uid", "Private sender", "private reason"))
            await { manager.activeNotifications.any { it.id == PokeNotifications.NOTIFICATION_ID } }
            val notification = manager.activeNotifications.single { it.id == PokeNotifications.NOTIFICATION_ID }.notification
            assertEquals(
                context.getString(R.string.poke_notification_received),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT),
            )
            assertFalse(notification.extras.toString().contains("private reason"))
            assertFalse(notification.extras.toString().contains("Private sender"))
            assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
            assertNotNull(notification.contentIntent)
            val channel = manager.getNotificationChannel(PokeNotifications.CHANNEL_ID)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            observer.setAppVisible(true)
            await { manager.activeNotifications.none { it.id == PokeNotifications.NOTIFICATION_ID } }
        } finally {
            observer.close()
            scope.cancel()
            notifications.clear()
        }
    }

    @Test fun deniedNotificationPermissionDoesNotBreakThePendingInAppNotice() {
        val notifications = PokeNotifications(context, permissionGranted = { false })
        notifications.clear()
        val store =
            ChatStore().apply {
                begin(1)
                bind(1, 1, ChatBinding(1, 1, 1, 1), participants = emptyList())
                receivePoke(1, 1, Ts3PokeEvent(2, "fixture-uid", "Sender", ""))
            }
        notifications.show()
        assertTrue(manager.activeNotifications.none { it.id == PokeNotifications.NOTIFICATION_ID })
        assertNotNull(store.state.value.pokeNotice)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Notification state did not settle", condition())
    }
}
