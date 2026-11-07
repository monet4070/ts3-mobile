package io.github.ts3mobile.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.R

/** OS policy controls delivery. Notification content contains no conversation information. */
internal class PrivateMessageNotifications(
    private val context: Context,
    private val permissionGranted: () -> Boolean = {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    },
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.private_message_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    fun show(
        token: String,
        onlyAlertOnce: Boolean,
    ) {
        if (!permissionGranted() || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
        try {
            manager.notify(NOTIFICATION_ID, build(token, onlyAlertOnce))
        } catch (_: SecurityException) {
            // Permission can be revoked after the check. In-app unread state is unaffected.
        }
    }

    fun clear() = manager.cancel(NOTIFICATION_ID)

    internal fun build(
        token: String,
        onlyAlertOnce: Boolean,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lucide_message_circle)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.private_message_notification_received))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    PrivateMessageNotificationIntent.create(context, token),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    internal companion object {
        const val CHANNEL_ID = "ts3_private_messages"
        const val NOTIFICATION_ID = 4105

        fun settingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
    }
}
