package io.github.ts3mobile.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.floatingPttSettingsIntent
import io.github.ts3mobile.app.probe.FloatingPttActivity
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ConnectedPttForegroundTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun visibleConnectedSessionCanPrepareMicrophoneTypeWithoutRecordingAndKeepListeningOnDisarm() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settingsEntry = checkNotNull(context.floatingPttSettingsIntent().component)
        assertEquals(context.packageName, settingsEntry.packageName)
        assertFalse(context.packageManager.getActivityInfo(settingsEntry, 0).exported)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(FloatingPttActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { service, binder ->
                val preparation = checkNotNull(binder.connectedPttPreparation)
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                instrumentation.runOnMainSync {
                    val local = Ts3Participant(1, 2, "Fixture", false, false, false)
                    service.mutableState.value =
                        service.mutableState.value.copy(
                            snapshot = SessionSnapshot(participants = listOf(local), ownClientId = local.id),
                        )
                    assertTrue(preparation.arm { })
                    assertEquals(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                        service.foregroundServiceType,
                    )
                    assertFalse(binder.state.value.isCapturing)
                    assertEquals(0L, preparation.framesProvided)
                }
                try {
                    val expected = context.getString(R.string.notification_floating_ptt_ready)
                    rule.waitUntil(5_000) {
                        val active = notificationManager.activeNotifications
                        val notification = active.firstOrNull { it.id == SessionNotifications.NOTIFICATION_ID }
                        notification?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == expected
                    }
                } finally {
                    instrumentation.runOnMainSync { preparation.disarm() }
                }
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
                assertFalse(binder.state.value.playbackMuted)
            }
        }
    }
}
