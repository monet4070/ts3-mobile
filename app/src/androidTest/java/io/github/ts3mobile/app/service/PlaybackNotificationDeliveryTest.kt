package io.github.ts3mobile.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.view.View
import android.widget.Button
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackNotificationDeliveryTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun offlineFixtureRemovesItsNotificationBeforeClosingTheActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { _, _ -> waitForAction(R.string.notification_mute_playback) }
            assertFalse(manager.activeNotifications.any { it.id == SessionNotifications.NOTIFICATION_ID })
        }
    }

    @Test fun immutableNotificationActionsMuteResumeAndShowRouteCheckWhenProtected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { service, _ ->
                val mute = waitForAction(R.string.notification_mute_playback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertTrue(mute.actionIntent.isImmutable)
                mute.actionIntent.send()
                rule.waitUntil(5_000) { service.mutableState.value.playbackMuted }
                mute.actionIntent.send()
                instrumentation.waitForIdleSync()
                assertTrue(service.mutableState.value.playbackMuted)
                val resume = waitForAction(R.string.notification_resume_playback)
                resume.actionIntent.send()
                rule.waitUntil(5_000) { !service.mutableState.value.playbackMuted }
                waitForAction(R.string.notification_mute_playback).actionIntent.send()
                rule.waitUntil(5_000) { service.mutableState.value.playbackMuted }
                val currentResume = waitForAction(R.string.notification_resume_playback)
                instrumentation.runOnMainSync {
                    val state = service.mutableState.value
                    service.mutableState.value =
                        state.copy(
                            playbackMuted = true,
                            audioRouting = state.audioRouting.copy(safetyMuted = true),
                        )
                    service.updateNotification()
                }
                waitForAction(R.string.notification_check_route)
                assertNull(action(R.string.notification_resume_playback))
                currentResume.actionIntent.send()
                instrumentation.waitForIdleSync()
                assertTrue(service.mutableState.value.playbackMuted)
            }
        }
    }

    @Test fun compactCardShowsBothButtonsAndDrivesGuardedListeningWithoutExpansion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { service, _ ->
                waitForAction(R.string.notification_mute_playback)
                instrumentation.runOnMainSync {
                    val notification = activeSessionNotification()
                    val compact = checkNotNull(notification.contentView).apply(context, null)
                    val disconnect = compact.findViewById<Button>(R.id.session_notification_disconnect)
                    val listening = compact.findViewById<Button>(R.id.session_notification_listening)
                    assertEquals(context.getString(R.string.notification_disconnect), disconnect.text.toString())
                    assertEquals(context.getString(R.string.notification_mute_playback), listening.text.toString())
                    assertTrue(disconnect.hasOnClickListeners())
                    assertTrue(listening.performClick())

                    val expanded = checkNotNull(notification.bigContentView).apply(context, null)
                    assertEquals(View.GONE, expanded.findViewById<View>(R.id.session_notification_controls).visibility)
                }
                rule.waitUntil(5_000) { service.mutableState.value.playbackMuted }
                waitForAction(R.string.notification_resume_playback)
                instrumentation.runOnMainSync {
                    val compact = checkNotNull(activeSessionNotification().contentView).apply(context, null)
                    val listening = compact.findViewById<Button>(R.id.session_notification_listening)
                    assertEquals(context.getString(R.string.notification_resume_playback), listening.text.toString())
                    assertTrue(listening.performClick())
                }
                rule.waitUntil(5_000) { !service.mutableState.value.playbackMuted }
                instrumentation.runOnMainSync {
                    assertFalse(service.mutableState.value.isTransmitting)
                    val state = service.mutableState.value
                    service.mutableState.value =
                        state.copy(playbackMuted = true, audioRouting = state.audioRouting.copy(safetyMuted = true))
                    service.updateNotification()
                }
                waitForAction(R.string.notification_check_route)
                instrumentation.runOnMainSync {
                    val compact = checkNotNull(activeSessionNotification().contentView).apply(context, null)
                    val listening = compact.findViewById<Button>(R.id.session_notification_listening)
                    assertEquals(context.getString(R.string.notification_check_route), listening.text.toString())
                    assertTrue(listening.hasOnClickListeners())
                }
            }
        }
    }

    @Test fun aBurstSettlesOnTheFinalRouteActionAndDoesNotApplyAStaleResume() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { service, _ ->
                waitForAction(R.string.notification_mute_playback).actionIntent.send()
                val oldResume = waitForAction(R.string.notification_resume_playback)
                instrumentation.runOnMainSync {
                    val initial = service.mutableState.value
                    repeat(30) { index ->
                        service.mutableState.value = initial.copy(playbackMuted = index % 2 == 0)
                        service.updateNotification()
                    }
                    service.mutableState.value = initial.copy(audioRouting = initial.audioRouting.copy(safetyMuted = true))
                    service.updateNotification()
                }
                waitForAction(R.string.notification_check_route)
                assertNull(action(R.string.notification_resume_playback))
                oldResume.actionIntent.send()
                instrumentation.waitForIdleSync()
                assertTrue(service.mutableState.value.playbackMuted)
                assertFalse(service.mutableState.value.isCapturing)
            }
        }
    }

    private fun activeSessionNotification(): Notification =
        manager.activeNotifications.single { it.id == SessionNotifications.NOTIFICATION_ID }.notification

    private fun action(label: Int): Notification.Action? =
        manager.activeNotifications.asSequence()
            .filter { it.id == SessionNotifications.NOTIFICATION_ID }
            .flatMap { it.notification.actions.orEmpty().asSequence() }
            .firstOrNull { it.title.toString() == context.getString(label) }

    private fun waitForAction(label: Int): Notification.Action {
        var found: Notification.Action? = null
        rule.waitUntil(5_000) { action(label).also { found = it } != null }
        return checkNotNull(found)
    }
}
