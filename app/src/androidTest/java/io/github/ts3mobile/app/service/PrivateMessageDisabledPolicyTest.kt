package io.github.ts3mobile.app.service

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in fixture: change OS policy BEFORE starting instrumentation, since revocation kills the app. */
class PrivateMessageDisabledPolicyTest {
    @Test fun disabledOsPolicySuppressesDeliveryWithoutChangingUnread() {
        val policy = InstrumentationRegistry.getArguments().getString("disabledNotificationPolicy")
        assumeTrue("Requires externally prepared app/channel policy fixture", policy == "app" || policy == "channel")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = PrivateMessageNotifications(context, permissionGranted = { true })
        notifications.clear()
        if (policy == "app") {
            assertFalse(manager.areNotificationsEnabled())
        } else {
            assertTrue(manager.areNotificationsEnabled())
            assertEquals(
                NotificationManager.IMPORTANCE_NONE,
                manager.getNotificationChannel(PrivateMessageNotifications.CHANNEL_ID).importance,
            )
        }
        val store =
            ChatStore().apply {
                begin(1)
                bind(1, 1, ChatBinding(1, 1, 10, 1))
                receive(1, 1, Ts3TextEvent(TextTargetMode.PRIVATE, 1, 2, "fixture-uid", "Fixture", "Synthetic private"))
            }
        val unread = store.state.value.totalUnread
        notifications.show("11111111-1111-4111-8111-111111111111", false)
        assertTrue(manager.activeNotifications.none { it.id == PrivateMessageNotifications.NOTIFICATION_ID })
        assertEquals(1, unread)
        assertEquals(unread, store.state.value.totalUnread)
    }
}
