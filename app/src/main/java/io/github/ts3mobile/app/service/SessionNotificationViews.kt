package io.github.ts3mobile.app.service

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.github.ts3mobile.app.R

internal fun NotificationCompat.Builder.showConnectedControls(
    context: Context,
    status: String,
    disconnect: NotificationCompat.Action,
    listening: NotificationCompat.Action?,
): NotificationCompat.Builder {
    val compact =
        RemoteViews(context.packageName, R.layout.session_notification).apply {
            setTextViewText(R.id.session_notification_status, status)
            if (context.resources.configuration.fontScale > 1.15f) {
                setViewVisibility(R.id.session_notification_status, View.GONE)
            }
            setTextViewText(R.id.session_notification_disconnect, disconnect.title)
            setOnClickPendingIntent(R.id.session_notification_disconnect, disconnect.actionIntent)
            if (listening != null) {
                setTextViewText(R.id.session_notification_listening, listening.title)
                setOnClickPendingIntent(R.id.session_notification_listening, listening.actionIntent)
            } else {
                setViewVisibility(R.id.session_notification_listening, View.GONE)
            }
        }
    val expanded =
        RemoteViews(context.packageName, R.layout.session_notification).apply {
            setTextViewText(R.id.session_notification_status, status)
            setViewVisibility(R.id.session_notification_controls, View.GONE)
        }
    return setStyle(NotificationCompat.DecoratedCustomViewStyle())
        .setCustomContentView(compact)
        .setCustomBigContentView(expanded)
}
