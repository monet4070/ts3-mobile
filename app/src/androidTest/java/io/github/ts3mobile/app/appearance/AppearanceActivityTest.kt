package io.github.ts3mobile.app.appearance

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.identity.IdentityManagementActivity
import io.github.ts3mobile.app.service.TeamSpeakService
import io.github.ts3mobile.protocol.ConnectionPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppearanceActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun realMenuSharesThemeWithMainAndIdentityWithoutRecreatingAppearanceOrStartingCapture() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val preferences = (context.applicationContext as Ts3MobileApplication).appearance
            val original = withTimeout(5000) { preferences.state.first { !it.busy } }.mode
            val connected = CompletableDeferred<TeamSpeakService.SessionBinder>()
            var callbacks = 0
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName?,
                        service: IBinder?,
                    ) {
                        callbacks++
                        connected.complete(service as TeamSpeakService.SessionBinder)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        callbacks++
                    }
                }
            val bound = context.bindService(Intent(context, TeamSpeakService::class.java), connection, Context.BIND_AUTO_CREATE)
            assertTrue(bound)
            try {
                val binder = withTimeout(5000) { connected.await() }
                assertEquals(ConnectionPhase.DISCONNECTED, binder.state.value.status.phase)
                waitForMain()
                rule.onNodeWithContentDescription("More options").performClick()
                rule.onNodeWithText("Appearance").performScrollTo().performClick()
                waitForText("Follow system")
                rule.onNodeWithText("Follow system").assertIsDisplayed()
                var appearance: AppearanceActivity? = null
                instrumentation.runOnMainSync { appearance = resumed().filterIsInstance<AppearanceActivity>().single() }
                rule.waitUntil(5000) { !preferences.state.value.busy }
                rule.onNodeWithText("Dark").performClick()
                rule.waitUntil(
                    5000,
                ) { preferences.state.value.status == ThemePreferenceStatus.SAVED && preferences.state.value.mode == ThemeMode.DARK }
                instrumentation.runOnMainSync {
                    assertSame(appearance, resumed().filterIsInstance<AppearanceActivity>().single())
                    val window = appearance!!.window
                    assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars)
                }
                rule.onNodeWithText("Close").performClick()
                waitForMain()
                rule.onNodeWithContentDescription("More options").assertIsDisplayed()
                rule.runOnIdle {
                    assertFalse(
                        WindowCompat.getInsetsController(
                            rule.activity.window,
                            rule.activity.window.decorView,
                        ).isAppearanceLightNavigationBars,
                    )
                }
                rule.onNodeWithContentDescription("More options").performClick()
                rule.onNodeWithText("Identity and backups").performScrollTo().performClick()
                waitForText("Export encrypted backup…")
                rule.onNodeWithText("Export encrypted backup…").performScrollTo().assertIsDisplayed()
                instrumentation.runOnMainSync {
                    val activity = resumed().filterIsInstance<IdentityManagementActivity>().single()
                    assertFalse(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
                rule.onNodeWithText("Close").performScrollTo().performClick()
                waitForMain()
                rule.onNodeWithContentDescription("More options").performClick()
                rule.onNodeWithText("Appearance").performScrollTo().performClick()
                waitForText("Light")
                rule.onNodeWithText("Light").performClick()
                rule.waitUntil(
                    5000,
                ) { preferences.state.value.status == ThemePreferenceStatus.SAVED && preferences.state.value.mode == ThemeMode.LIGHT }
                instrumentation.runOnMainSync {
                    val activity = resumed().filterIsInstance<AppearanceActivity>().single()
                    assertTrue(WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars)
                }
                rule.onNodeWithText("Close").performClick()
                assertEquals(1, callbacks)
                assertEquals(ConnectionPhase.DISCONNECTED, binder.state.value.status.phase)
                assertFalse(binder.state.value.isCapturing)
            } finally {
                instrumentation.runOnMainSync {
                    resumed().filter { it !is MainActivity }.forEach { it.finish() }
                }
                withTimeout(5000) { preferences.state.first { !it.busy } }
                preferences.select(original)
                withTimeout(5000) { preferences.state.first { !it.busy } }
                context.unbindService(connection)
            }
        }

    private fun resumed() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()

    private fun waitForMain() {
        rule.waitUntil(5000) { runCatching { rule.onNodeWithContentDescription("More options").fetchSemanticsNode() }.isSuccess }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5000) { runCatching { rule.onNodeWithText(text).fetchSemanticsNode() }.isSuccess }
    }
}
