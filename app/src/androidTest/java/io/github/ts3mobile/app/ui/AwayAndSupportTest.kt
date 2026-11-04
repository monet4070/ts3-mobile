package io.github.ts3mobile.app.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.PROJECT_REPOSITORY_URL
import io.github.ts3mobile.app.openProjectRepository
import io.github.ts3mobile.protocol.OwnPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AwayAndSupportTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun awayShortcutIsAnIconWithALargeTouchTargetAndOpensTheExistingDialog() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(presence = fixture.state.value.presence.copy(confirmed = OwnPresence()))
        rule.setContent { fixture.Render(locale = "zh-CN") }
        val action = rule.onNodeWithContentDescription("暂离与状态").assertIsDisplayed()
        val node = action.fetchSemanticsNode()
        assertFalse(node.config.contains(SemanticsProperties.Text))
        val minimum = with(rule.density) { 48.dp.toPx() }
        assertTrue(node.boundsInRoot.width >= minimum && node.boundsInRoot.height >= minimum)
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "在线"))
        save("away-icon-available")
        saveIcon("away-icon-detail-available")
        action.performClick()
        rule.onNodeWithText("暂时离开").assertIsDisplayed()
    }

    @Test fun confirmedAwayHasAnEmphasizedIconInTheWarmLightPalette() = awayPreview(dark = false)

    @Test fun confirmedAwayHasAnEmphasizedIconInTheWarmDarkPalette() = awayPreview(dark = true)

    @Test fun pendingAwayExposesPendingStateRatherThanClaimingConfirmation() {
        val fixture = UiFixture(connected = true)
        fixture.state.value =
            fixture.state.value.copy(
                presence =
                    fixture.state.value.presence.copy(
                        away = true, pending = true, confirmed = OwnPresence(),
                    ),
            )
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Away and status")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Synchronizing with server…"))
    }

    @Test fun starMenuIsAvailableWithoutAConnectionAndInvokesOnlyTheRepositoryAction() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("给项目点个 Star").performScrollTo().assertIsDisplayed()
        save("support-menu-light", popup = true)
        rule.onNodeWithText("给项目点个 Star").performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.repositoryOpens)
            assertEquals(0, fixture.connects)
            assertTrue(fixture.awayRequests.isEmpty())
        }
        rule.onNodeWithText("给项目点个 Star").assertDoesNotExist()
    }

    @Test fun starMenuFitsNarrowLargeTextAndReleasesPttBeforeOpening() {
        val fixture = UiFixture(connected = true)
        rule.setContent { Box(Modifier.width(320.dp)) { fixture.Render(dark = true, locale = "zh-CN", fontScale = 1.8f) } }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("给项目点个 Star").performScrollTo().assertIsDisplayed()
        save("support-menu-dark-large", popup = true)
        rule.onNodeWithText("给项目点个 Star").performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.repositoryOpens)
            assertEquals(false, fixture.transmission.last())
        }
    }

    @Test fun repositoryActionDispatchesTheVerifiedPublicGithubUrl() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var dispatched: Intent? = null
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                    dispatched = intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
        instrumentation.addMonitor(monitor)
        try {
            rule.runOnUiThread { openProjectRepository(rule.activity) }
            rule.runOnIdle {
                val intent = checkNotNull(dispatched)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https://github.com/monet4070/ts3-mobile", intent.dataString)
                assertEquals(PROJECT_REPOSITORY_URL, intent.dataString)
                assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
                assertTrue(intent.extras == null || intent.extras!!.isEmpty)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun awayPreview(dark: Boolean) {
        val fixture = UiFixture(connected = true)
        fixture.state.value =
            fixture.state.value.copy(
                presence = fixture.state.value.presence.copy(away = true, confirmed = OwnPresence(away = true)),
            )
        rule.setContent { fixture.Render(dark = dark, locale = "zh-CN") }
        rule.onNodeWithContentDescription("暂离与状态")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "暂离中"))
        save("away-confirmed-$dark")
        saveIcon("away-icon-detail-$dark")
    }

    private fun saveIcon(name: String) {
        val bitmap = rule.onNodeWithContentDescription("暂离与状态").captureToImage()
        writeImage(name, bitmap.asAndroidBitmap())
    }

    private fun save(
        name: String,
        popup: Boolean = false,
    ) {
        val node = if (popup) rule.onNode(isPopup()) else rule.onRoot()
        writeImage(name, node.captureToImage().asAndroidBitmap())
    }

    private fun writeImage(
        name: String,
        bitmap: Bitmap,
    ) {
        val output = File(rule.activity.getExternalFilesDir(null), "away-support").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
