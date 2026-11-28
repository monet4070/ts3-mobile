package io.github.ts3mobile.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.identity.IdentityExportState
import io.github.ts3mobile.app.identity.IdentityExportStatus
import io.github.ts3mobile.app.identity.IdentityManagementScreen
import io.github.ts3mobile.app.identity.IdentityRestoreSection
import io.github.ts3mobile.app.identity.IdentityRestoreState
import io.github.ts3mobile.app.identity.IdentitySnapshot
import io.github.ts3mobile.app.identity.IdentityUseState
import io.github.ts3mobile.app.probe.FloatingPttSettingsScreen
import io.github.ts3mobile.app.probe.FloatingPttStyle
import io.github.ts3mobile.app.service.BackgroundRuntimeState
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class SettingsDetailLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun identityActionsAndPasswordDialogRemainReachableAtLargeText() {
        val label = "Password (at least 12 characters)"
        rule.setContent {
            ConfiguredTheme("en", 1.8f, dark = true) {
                Box(Modifier.width(320.dp)) { IdentityFixture() }
            }
        }
        savePreview("identity-en-dark-large.png")
        rule.onNodeWithText(label).assertDoesNotExist()
        val create = rule.onNodeWithText("Create encrypted backup")
        create.performScrollTo().performClick()
        rule.onNodeWithText(label).performScrollTo().performTextInput("synthetic draft")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Restore from backup").performScrollTo().performClick()
        rule.onNodeWithText("Choose backup file").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        create.performScrollTo().performClick()
        rule.onNodeWithText(label).performScrollTo().assertTextEquals(label, "")
    }

    @Test fun chineseIdentityFormsShareTheSameVisualSections() {
        rule.setContent { ConfiguredTheme("zh-CN", 1f, dark = false) { IdentityFixture() } }
        rule.onNodeWithText("创建加密备份").assertIsDisplayed()
        savePreview("identity-export-zh.png")
        rule.onNodeWithText("恢复上一个身份").assertDoesNotExist()
        rule.onNodeWithText("从备份恢复").performScrollTo().performClick()
        savePreview("identity-restore-zh.png", includeDialog = true)
        rule.onNodeWithText("原备份密码").assertIsDisplayed()
        rule.onNodeWithText("原备份密码").performTextInput("synthetic restore password")
        rule.onNodeWithText("选择备份文件").assertIsDisplayed()
    }

    @Test fun backgroundStatusAndSwitchStayReadableAndInteractiveAtLargeText() {
        val state = mutableStateOf(BackgroundRuntimeState(batteryOptimizationExempt = true))
        rule.setContent {
            ConfiguredTheme("en", 1.8f, dark = true) {
                Box(Modifier.width(320.dp)) {
                    BackgroundRuntimeScreen(
                        state.value,
                        remember { SnackbarHostState() },
                        { state.value = state.value.copy(keepCpuAwake = it) },
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        rule.onNodeWithText("Battery optimization exemption").performScrollTo().assertIsDisplayed()
        val label = rule.onNodeWithText("Battery optimization exemption", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = rule.onNodeWithText("Yes", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(value.top >= label.bottom)
        savePreview("background-en-dark-large.png")
        val toggle = rule.onNodeWithText("Keep CPU awake for voice").performScrollTo()
        toggle.assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("Open app background settings").performScrollTo().assertIsDisplayed()
    }

    @Test fun chineseFloatingPttDistinguishesAutoShowFromTheManualAction() {
        rule.setContent {
            ConfiguredTheme("zh-CN", 1f, dark = false) {
                FloatingPttSettingsScreen(true, true, {}, {}, {}, true, {}, FloatingPttStyle.STANDARD, {})
            }
        }
        rule.onNodeWithText("标准浮窗").assertIsSelected()
        rule.onNodeWithText("极简浮窗").assertIsDisplayed()
        rule.onNodeWithText("退到后台时自动显示浮窗").performScrollTo().assertIsOn()
        rule.onNodeWithText("开启浮窗").performScrollTo().assertIsDisplayed()
        savePreview("floating-auto-zh.png")
    }

    private fun savePreview(
        filename: String,
        includeDialog: Boolean = false,
    ) {
        if (InstrumentationRegistry.getArguments().getString("captureSettingsReview") != "true") return
        rule.waitForIdle()
        val preview =
            if (includeDialog) {
                checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            } else {
                rule.onRoot().captureToImage().asAndroidBitmap()
            }
        val folder = File(rule.activity.filesDir, "settings-review").apply { mkdirs() }
        File(folder, filename).outputStream().use { output ->
            check(preview.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
}

@Composable
private fun IdentityFixture() {
    val summary = Ts3IdentitySummary("SyntheticIdentityUID0000000", 218, 8)
    val state = IdentityExportState(IdentitySnapshot(summary, null, false, "fixture"), IdentityExportStatus.READY)
    IdentityManagementScreen(state, IdentityUseState.IDLE, { _, _ -> }, {}, {}, {}, restoreContent = {
        IdentityRestoreSection(IdentityRestoreState(), IdentityUseState.IDLE, false, false, {}, {}, {}, {}, {}, {})
    })
}

@Composable
private fun ConfiguredTheme(
    language: String,
    fontScale: Float,
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val base = LocalContext.current
    val configuration =
        remember(base, language) { Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) } }
    val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides context,
        LocalDensity provides Density(LocalDensity.current.density, fontScale),
    ) { Ts3MobileTheme(dark, content) }
}
