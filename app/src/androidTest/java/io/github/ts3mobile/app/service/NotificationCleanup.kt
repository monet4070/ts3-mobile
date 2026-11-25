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
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(notificationId)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (manager.activeNotifications.any { it.id == notificationId }) {
            check(SystemClock.uptimeMillis() < deadline) { "Fixture notification $notificationId did not clear" }
            SystemClock.sleep(20)
        }
    }
}
