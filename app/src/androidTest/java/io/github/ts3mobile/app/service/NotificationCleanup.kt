package io.github.ts3mobile.app.service

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

class NotificationCleanup(private val notificationId: Int) : ExternalResource() {
    override fun before() = clear()

    override fun after() = clear()

    private fun clear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSystemService(NotificationManager::class.java).cancelFixtureNotification(notificationId)
    }
}

internal fun NotificationManager.cancelFixtureNotification(notificationId: Int) {
    cancel(notificationId)
    awaitFixtureNotificationRemoval(notificationId)
}

internal fun NotificationManager.awaitFixtureNotificationRemoval(notificationId: Int) {
    val deadline = SystemClock.uptimeMillis() + 5_000
    while (activeNotifications.any { it.id == notificationId }) {
        check(SystemClock.uptimeMillis() < deadline) { "Fixture notification $notificationId did not clear" }
        SystemClock.sleep(20)
    }
}
