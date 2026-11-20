package io.github.ts3mobile.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

internal object SessionNotificationChannel {
    const val ID = "ts3_connection_controls"
    const val LEGACY_ID = "ts3_connection"

    fun register(
        manager: NotificationManager,
        name: String,
    ): String {
        val legacy = manager.getNotificationChannel(LEGACY_ID)
        if (legacy != null && !hasOriginalDefaults(legacy)) return legacy.id

        val channel =
            NotificationChannel(ID, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        manager.createNotificationChannel(channel)
        if (legacy != null) {
            if (manager.getNotificationChannel(ID) == null) return legacy.id
            if (manager.activeNotifications.none { it.notification.channelId == LEGACY_ID }) {
                manager.deleteNotificationChannel(LEGACY_ID)
            }
        }
        return ID
    }

    fun hasOriginalDefaults(legacy: NotificationChannel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || legacy.hasUserSetImportance()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && legacy.hasUserSetSound()) return false
        val defaults = NotificationChannel(LEGACY_ID, legacy.name, NotificationManager.IMPORTANCE_LOW)
        return legacy.importance == defaults.importance &&
            legacy.sound == defaults.sound &&
            legacy.shouldVibrate() == defaults.shouldVibrate() &&
            legacy.shouldShowLights() == defaults.shouldShowLights() &&
            legacy.canShowBadge() == defaults.canShowBadge() &&
            legacy.canBypassDnd() == defaults.canBypassDnd() &&
            legacy.group == defaults.group &&
            legacy.lockscreenVisibility == defaults.lockscreenVisibility
    }
}
