package io.github.ts3mobile.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream

class PrivateMessageNotificationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"

    @Test fun notificationIsGenericPublicImmutableAndSummaryUpdatesAreSilent() {
        val notifications = PrivateMessageNotifications(context)
        val initial = notifications.build(first, false)
        val update = notifications.build(second, true)
        assertEquals(
            context.getString(R.string.private_message_notification_received),
            initial.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(context.getString(R.string.app_name), initial.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(Notification.VISIBILITY_PUBLIC, initial.visibility)
        assertFalse(initial.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(update.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNotNull(initial.contentIntent)
        assertNotEquals(initial.contentIntent, update.contentIntent)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(initial.contentIntent.isImmutable)
        assertNotEquals(PokeNotifications.CHANNEL_ID, PrivateMessageNotifications.CHANNEL_ID)
        val channel = manager.getNotificationChannel(PrivateMessageNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertFalse(channel.canBypassDnd())
    }

    @Test fun intentContainsOnlyOpaqueTokenAndRejectsUntrustedShapes() {
        val intent = PrivateMessageNotificationIntent.create(context, first)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull(intent.extras)
        assertEquals(first, PrivateMessageNotificationIntent.token(intent))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertNull(PrivateMessageNotificationIntent.token(Intent(intent).setAction(Intent.ACTION_VIEW)))
        assertNull(
            PrivateMessageNotificationIntent.token(
                Intent(intent).setData(intent.data?.buildUpon()?.appendQueryParameter("uid", "fixture")?.build()),
            ),
        )
        assertNull(PrivateMessageNotificationIntent.token(PrivateMessageNotificationIntent.create(context, "fixture-uid")))
    }

    @Test fun deniedPermissionDoesNotPostAndGrantedPermissionPostsThenClears() {
        val denied = PrivateMessageNotifications(context, permissionGranted = { false })
        denied.clear()
        denied.show(first, false)
        assertTrue(manager.activeNotifications.none { it.id == PrivateMessageNotifications.NOTIFICATION_ID })
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val notifications = PrivateMessageNotifications(context)
        try {
            notifications.show(first, false)
            await { manager.activeNotifications.any { it.id == PrivateMessageNotifications.NOTIFICATION_ID } }
            notifications.clear()
            await { manager.activeNotifications.none { it.id == PrivateMessageNotifications.NOTIFICATION_ID } }
        } finally {
            notifications.clear()
        }
    }

    @Test fun settingsOpensTheIndependentChannel() {
        val intent = PrivateMessageNotifications.settingsIntent(context)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(PrivateMessageNotifications.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertNotNull(intent.resolveActivity(context.packageManager))
    }

    @Test fun doNotDisturbPolicyIsPreservedAndChannelCannotBypassIt() {
        grantPermission()
        val original = manager.currentInterruptionFilter
        val restoreMode =
            when (original) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                else -> "all"
            }
        val notifications = PrivateMessageNotifications(context)
        try {
            shell("cmd notification set_dnd none")
            await { manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE }
            notifications.show(first, false)
            await { manager.activeNotifications.any { it.id == PrivateMessageNotifications.NOTIFICATION_ID } }
            assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, manager.currentInterruptionFilter)
            assertFalse(manager.getNotificationChannel(PrivateMessageNotifications.CHANNEL_ID).canBypassDnd())
        } finally {
            notifications.clear()
            shell("cmd notification set_dnd $restoreMode")
        }
    }

    private fun grantPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Notification state did not settle", condition())
    }
}
