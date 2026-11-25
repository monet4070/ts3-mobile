package io.github.ts3mobile.app.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.floatingPttRecallIntent
import io.github.ts3mobile.app.floatingPttSettingsIntent
import io.github.ts3mobile.app.identity.IdentityManagementActivity
import io.github.ts3mobile.app.probe.FloatingPttActivity
import io.github.ts3mobile.app.probe.autoShowFloatingPtt
import io.github.ts3mobile.protocol.SessionSnapshot
import io.github.ts3mobile.protocol.Ts3Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class FloatingPttRecallNotificationTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val label = context.getString(R.string.notification_show_floating_ptt)

    @Test fun connectedNotificationOffersAPrivateActivityAndHidesTheActionWhenIneligible() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val recall = checkNotNull(context.floatingPttRecallIntent())
        val component = checkNotNull(recall.component)
        assertFalse(context.packageManager.getActivityInfo(component, 0).exported)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(FloatingPttActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { service, _ ->
                publishCurrentChannel(service)
                rule.waitUntil(5_000) { currentNotification()?.recallAction() != null }
                val notification = checkNotNull(currentNotification())
                val action = checkNotNull(notification.recallAction())
                assertEquals(context.packageName, action.actionIntent.creatorPackage)
                assertTrue(action.actionIntent.isActivity)
                assertTrue(action.actionIntent.isImmutable)
                instrumentation.runOnMainSync {
                    val button = compactRecallButton(notification)
                    assertEquals(label, button.text.toString())
                    assertEquals(View.VISIBLE, button.visibility)
                    assertTrue(button.hasOnClickListeners())
                    service.mutableState.value = service.mutableState.value.copy(switchingChannelId = 7)
                    service.updateNotification()
                }
                rule.waitUntil(5_000) { currentNotification()?.let { it.recallAction() == null } == true }
                instrumentation.runOnMainSync {
                    assertEquals(View.GONE, compactRecallButton(checkNotNull(currentNotification())).visibility)
                }
            }
        }
    }

    @Test fun tappingTheSystemNotificationRecallsTheOverlayWithoutOpeningTheMicrophone() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        assumeTrue(InstrumentationRegistry.getArguments().getString("exercisePttRecall") == "true")
        assumeTrue(Settings.canDrawOverlays(context))
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(FloatingPttActivity::class.java).use {
            instrumentation.withOfflineConnectedSession { service, binder ->
                val preparation = checkNotNull(binder.connectedPttPreparation)
                publishCurrentChannel(service)
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                repeat(2) {
                    rule.waitUntil(5_000) { currentNotification()?.recallAction() != null }
                    assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
                    rule.waitUntil(8_000) { visibleRecallButton() != null }
                    val button = visibleRecallButton()
                    assertNotNull(button)
                    assertTrue(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                    try {
                        rule.waitUntil(8_000) { overlayIsVisible() }
                        instrumentation.runOnMainSync { assertTrue(preparation.isValid) }
                        assertFalse(binder.state.value.isCapturing)
                        assertFalse(binder.state.value.actualAudioDevices.recording)
                        assertEquals(0L, preparation.framesProvided)
                    } finally {
                        instrumentation.runOnMainSync { preparation.disarm() }
                    }
                    rule.waitUntil(5_000) { !overlayIsVisible() }
                }
            }
        }
    }

    @Test fun homeShowsTheOptedInOverlaySilentlyAndReturningHidesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        assumeTrue(InstrumentationRegistry.getArguments().getString("exercisePttRecall") == "true")
        assumeTrue(Settings.canDrawOverlays(context))
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val savedPreference = context.autoShowFloatingPtt
        try {
            context.autoShowFloatingPtt = false
            val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val mainActivity = instrumentation.startActivitySync(launch) as MainActivity
            try {
                rule.waitUntil(5_000) { mainActivity.matchesLifecycle(Lifecycle.State.RESUMED) }
                instrumentation.withOfflineConnectedSession { service, binder ->
                    val preparation = checkNotNull(binder.connectedPttPreparation)
                    publishCurrentChannel(service)
                    assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                    rule.waitUntil(5_000) { mainActivity.matchesLifecycle(Lifecycle.State.CREATED) }
                    assertFalse(overlayIsVisible())
                    reopenMainActivity()
                    rule.waitUntil(5_000) { mainActivity.matchesLifecycle(Lifecycle.State.RESUMED) }
                    context.autoShowFloatingPtt = true
                    instrumentation.runOnMainSync {
                        mainActivity.startActivity(Intent(mainActivity, IdentityManagementActivity::class.java))
                    }
                    rule.waitUntil(5_000) { mainActivity.matchesLifecycle(Lifecycle.State.CREATED) }
                    instrumentation.runOnMainSync { assertFalse(preparation.isValid) }
                    assertFalse(overlayIsVisible())
                    runShell("input keyevent KEYCODE_BACK")
                    rule.waitUntil(5_000) { mainActivity.matchesLifecycle(Lifecycle.State.RESUMED) }
                    repeat(2) {
                        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                        rule.waitUntil(8_000) { overlayIsVisible() }
                        instrumentation.runOnMainSync { assertTrue(preparation.isValid) }
                        assertFalse(binder.state.value.isCapturing)
                        assertFalse(binder.state.value.actualAudioDevices.recording)
                        assertEquals(0L, preparation.framesProvided)
                        reopenMainActivity()
                        rule.waitUntil(5_000) { mainActivity.matchesLifecycle(Lifecycle.State.RESUMED) && !overlayIsVisible() }
                    }
                    val setup = context.floatingPttSettingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val settingsActivity = instrumentation.startActivitySync(setup) as FloatingPttActivity
                    try {
                        rule.waitUntil(5_000) { settingsActivity.matchesLifecycle(Lifecycle.State.RESUMED) }
                        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                        rule.waitUntil(8_000) { overlayIsVisible() }
                        assertFalse(binder.state.value.actualAudioDevices.recording)
                        assertEquals(0L, preparation.framesProvided)
                    } finally {
                        instrumentation.runOnMainSync {
                            preparation.disarm()
                            settingsActivity.finish()
                        }
                    }
                }
            } finally {
                instrumentation.runOnMainSync { mainActivity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            context.autoShowFloatingPtt = savedPreference
        }
    }

    private fun reopenMainActivity() {
        runShell("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
    }

    private fun ComponentActivity.matchesLifecycle(expected: Lifecycle.State): Boolean {
        var matches = false
        instrumentation.runOnMainSync {
            matches = lifecycle.currentState == expected && (expected != Lifecycle.State.RESUMED || hasWindowFocus())
        }
        return matches
    }

    private fun runShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun publishCurrentChannel(service: TeamSpeakService) {
        instrumentation.runOnMainSync {
            val local = Ts3Participant(1, 2, "Fixture", false, false, false)
            service.mutableState.value =
                service.mutableState.value.copy(snapshot = SessionSnapshot(participants = listOf(local), ownClientId = local.id))
            service.updateNotification()
        }
    }

    private fun currentNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == SessionNotifications.NOTIFICATION_ID }?.notification

    private fun Notification.recallAction(): Notification.Action? = actions.orEmpty().firstOrNull { it.title.toString() == label }

    private fun visibleRecallButton(): AccessibilityNodeInfo? =
        automation.windows.asSequence().mapNotNull { it.root }.flatMap { root -> allNodes(root) }.firstOrNull { node ->
            node.isVisibleToUser && node.text?.toString().equals(label, ignoreCase = true)
        }?.let { node -> if (node.isClickable) node else node.parent?.takeIf { it.isClickable } }

    private fun allNodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> =
        sequence {
            yield(root)
            repeat(root.childCount) { index -> root.getChild(index)?.let { yieldAll(allNodes(it)) } }
        }

    private fun compactRecallButton(notification: Notification): Button =
        checkNotNull(notification.contentView).apply(context, null).findViewById(R.id.session_notification_show_ptt)

    private fun overlayIsVisible(): Boolean {
        val dump =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows")).bufferedReader().use {
                it.readText()
            }
        return dump.split(Regex("(?m)^\\s*Window #\\d+ Window\\{")).any {
            "package=${context.packageName} " in it && "ty=APPLICATION_OVERLAY" in it && "isVisible=true" in it
        }
    }
}
