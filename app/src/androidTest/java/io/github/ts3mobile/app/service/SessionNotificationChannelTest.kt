package io.github.ts3mobile.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SessionNotificationChannelTest {
    @Test fun onlyAnUnmodifiedLegacyChannelIsEligibleForMigration() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assertTrue(SessionNotificationChannel.hasOriginalDefaults(legacyChannel()))
        assertFalse(SessionNotificationChannel.hasOriginalDefaults(legacyChannel(NotificationManager.IMPORTANCE_NONE)))
        assertFalse(SessionNotificationChannel.hasOriginalDefaults(legacyChannel().apply { setSound(null, null) }))
        assertFalse(SessionNotificationChannel.hasOriginalDefaults(legacyChannel().apply { enableVibration(true) }))
        assertFalse(SessionNotificationChannel.hasOriginalDefaults(legacyChannel().apply { setShowBadge(false) }))
    }

    @Test fun unmodifiedLegacyChannelIsReplacedWithDefaultImportanceWithoutAlerting() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedNotificationChannels") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(legacyChannel())
        val selected = SessionNotificationChannel.register(manager, "Connection test")
        assertEquals(SessionNotificationChannel.ID, selected)
        assertNull(manager.getNotificationChannel(SessionNotificationChannel.LEGACY_ID))
        val channel = checkNotNull(manager.getNotificationChannel(selected))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(selected, SessionNotificationChannel.register(manager, "Connection test"))
    }

    private fun legacyChannel(importance: Int = NotificationManager.IMPORTANCE_LOW) =
        NotificationChannel(SessionNotificationChannel.LEGACY_ID, "Legacy test", importance)
}
