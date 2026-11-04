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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R

/** A generic alert: sender, server, reason and identity never enter notification content or extras. */
internal class PokeNotifications(
    private val context: Context,
    private val permissionGranted: () -> Boolean = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    },
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.poke_notification_channel), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    fun show() {
        if (!permissionGranted()) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        manager.notify(NOTIFICATION_ID, build())
    }

    fun clear() = manager.cancel(NOTIFICATION_ID)

    internal fun build(): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_poke)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.poke_notification_received))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The entire content is generic, so it is safe to show on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    internal companion object {
        const val CHANNEL_ID = "ts3_pokes"
        const val NOTIFICATION_ID = 4104
    }
}
