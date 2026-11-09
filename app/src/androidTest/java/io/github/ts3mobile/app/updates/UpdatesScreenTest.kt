package io.github.ts3mobile.app.updates

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class UpdatesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val installed = InstalledAppVersion("1.0.0", 14, OFFICIAL_APPLICATION_ID, 35, setOf("b".repeat(64)))

    @Test fun availableReleaseExposesExplicitDownloadAndSwitchingChannelsRemovesTheOldResult() {
        val state =
            mutableStateOf(
                UpdateState(UpdateChannel.INCLUDE_PRERELEASES, UpdatePreferenceStatus.SAVED, UpdateCheck.Complete(updateUiRelease())),
            )
        var downloads = 0
        rule.setContent {
            Ts3MobileTheme(false) {
                UpdatesScreen(state.value, installed, {
                    state.value = UpdateState(it, UpdatePreferenceStatus.SAVED, UpdateCheck.Checking)
                }, {}, {}, {}, { downloads++ }, {})
            }
        }
        rule.onNodeWithText("An update is available").performScrollTo().assertIsDisplayed()
        save("updates-en-available")
        rule.onNodeWithText("Download APK").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, downloads) }
        rule.onNodeWithContentDescription("Include prereleases").performScrollTo().performClick()
        rule.onNodeWithText("Download APK").assertDoesNotExist()
        rule.onNodeWithText("Checking GitHub Releases…").assertIsDisplayed()
    }

    @Test fun equalAndOlderVersionsNeverOfferADownload() {
        val state =
            mutableStateOf(
                UpdateState(
                    preferenceStatus = UpdatePreferenceStatus.SAVED,
                    check = UpdateCheck.Complete(updateUiRelease().copy(versionCode = 14)),
                ),
            )
        rule.setContent { Ts3MobileTheme(false) { UpdatesScreen(state.value, installed, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Your installed version is current or newer").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Download APK").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(check = UpdateCheck.Complete(updateUiRelease().copy(versionCode = 13))) }
        rule.onNodeWithText("Download APK").assertDoesNotExist()
    }

    @Test fun failuresAndUnavailablePreferencesCannotLookLikeSuccessfulChecks() {
        val state =
            mutableStateOf(UpdateState(preferenceStatus = UpdatePreferenceStatus.SAVED, check = UpdateCheck.Failed(UpdateFailure.NETWORK)))
        var retries = 0
        rule.setContent { Ts3MobileTheme(false) { UpdatesScreen(state.value, installed, {}, {}, { retries++ }, {}, {}, {}) } }
        rule.onNodeWithText("Could not reach GitHub. Check your connection and try again.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Your installed version is current or newer").assertDoesNotExist()
        rule.onNodeWithText("Download APK").assertDoesNotExist()
        rule.runOnIdle { state.value = UpdateState(preferenceStatus = UpdatePreferenceStatus.READ_FAILED) }
        rule.onNodeWithContentDescription("Include prereleases").assertIsNotEnabled()
        rule.onNodeWithText("Check again").assertIsNotEnabled()
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun chineseLargeTextLongNotesKeepDownloadAndCloseReachable() {
        var downloads = 0
        var closes = 0
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, 1.8f),
            ) {
                Ts3MobileTheme(true) {
                    UpdatesScreen(
                        UpdateState(
                            UpdateChannel.INCLUDE_PRERELEASES,
                            UpdatePreferenceStatus.SAVED,
                            UpdateCheck.Complete(updateUiRelease().copy(notes = longUpdateNotes)),
                        ),
                        installed,
                        {},
                        {},
                        {},
                        {},
                        { downloads++ },
                        { closes++ },
                    )
                }
            }
        }
        val closeTop = rule.onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithText("下载 APK").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("展开更新说明").performScrollTo().performClick()
        repeat(3) { rule.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        rule.onNodeWithText("关闭").assertIsDisplayed()
        rule.onNodeWithText("收起更新说明").assertIsDisplayed()
        assertEquals(closeTop, rule.onNodeWithText("关闭").fetchSemanticsNode().boundsInRoot.top)
        save("updates-zh-large-dark")
        rule.onNodeWithText("收起更新说明").performClick()
        rule.onNodeWithText("展开更新说明").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.runOnIdle { assertEquals(1 to 1, downloads to closes) }
    }

    @Test fun scrollingLongNotesKeepsControlsFixedAndCollapseReturnsToTheSamePosition() {
        rule.setContent {
            Ts3MobileTheme(false) {
                UpdatesScreen(
                    UpdateState(
                        preferenceStatus = UpdatePreferenceStatus.SAVED,
                        check = UpdateCheck.Complete(updateUiRelease().copy(notes = longUpdateNotes)),
                    ),
                    installed,
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        val closeBounds = rule.onNodeWithText("Close").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Show full notes").performScrollTo()
        val expandBounds = rule.onNodeWithText("Show full notes").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Show full notes").performClick()
        save("updates-en-markdown-top")
        repeat(3) { rule.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        rule.onNodeWithText("Show less").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed()
        assertEquals(closeBounds, rule.onNodeWithText("Close").fetchSemanticsNode().boundsInRoot)
        save("updates-en-markdown-reader")
        rule.onNodeWithText("Show less").performClick()
        rule.onNodeWithText("Show full notes").assertIsDisplayed()
        assertEquals(expandBounds, rule.onNodeWithText("Show full notes").fetchSemanticsNode().boundsInRoot)
        save("updates-en-returned-summary")
    }

    @Test fun chineseReaderCanCloseDirectlyWithoutCollapsing() {
        var closes = 0
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
            ) {
                Ts3MobileTheme(false) {
                    UpdatesScreen(
                        UpdateState(
                            preferenceStatus = UpdatePreferenceStatus.SAVED,
                            check = UpdateCheck.Complete(updateUiRelease().copy(notes = longUpdateNotes)),
                        ),
                        installed,
                        {},
                        {},
                        {},
                        {},
                        {},
                        { closes++ },
                    )
                }
            }
        }
        save("updates-zh-summary")
        rule.onNodeWithText("展开更新说明").performScrollTo().performClick()
        save("updates-zh-markdown-top")
        rule.onNodeWithText("收起更新说明").assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun readingStateSurvivesRecreationAndBackCollapsesBeforeClosingThePage() {
        val restore = StateRestorationTester(rule)
        var closes = 0
        restore.setContent {
            Ts3MobileTheme(false) {
                UpdatesScreen(
                    UpdateState(
                        preferenceStatus = UpdatePreferenceStatus.SAVED,
                        check = UpdateCheck.Complete(updateUiRelease().copy(notes = longUpdateNotes)),
                    ),
                    installed,
                    {},
                    {},
                    {},
                    {},
                    {},
                    { closes++ },
                )
            }
        }
        rule.onNodeWithText("Show full notes").performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Show less").assertIsDisplayed()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Show less").assertDoesNotExist()
        rule.onNodeWithText("Show full notes").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun aDifferentResultDoesNotKeepThePreviousNotesReaderOpen() {
        val state =
            mutableStateOf(
                UpdateState(
                    preferenceStatus = UpdatePreferenceStatus.SAVED,
                    check = UpdateCheck.Complete(updateUiRelease().copy(notes = longUpdateNotes)),
                ),
            )
        rule.setContent { Ts3MobileTheme(false) { UpdatesScreen(state.value, installed, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithText("Show full notes").performScrollTo().performClick()
        rule.runOnIdle { state.value = state.value.copy(check = UpdateCheck.Checking) }
        rule.onNodeWithText("Show less").assertDoesNotExist()
        rule.onNodeWithText("Checking GitHub Releases…").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(check = UpdateCheck.Complete(updateUiRelease().copy(versionCode = 16))) }
        rule.onNodeWithText("Download APK").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show less").assertDoesNotExist()
    }

    @Test fun unsupportedAndroidDisablesDownloadAndDifferentSigningExplainsCompatibility() {
        val installedState = mutableStateOf(installed.copy(sdk = 25))
        rule.setContent {
            Ts3MobileTheme(false) {
                UpdatesScreen(
                    UpdateState(
                        preferenceStatus = UpdatePreferenceStatus.SAVED,
                        check = UpdateCheck.Complete(updateUiRelease()),
                    ),
                    installedState.value,
                    {
                    },
                    {},
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        rule.onNodeWithText("Download APK").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { installedState.value = installed.copy(certificates = setOf("c".repeat(64))) }
        rule.onNodeWithText(
            "This release uses a different app ID or signing certificate. An in-place upgrade may be refused. " +
                "Do not uninstall or clear app data to resolve this.",
        ).performScrollTo().assertIsDisplayed()
    }

    private fun save(name: String) {
        val dir = File(rule.activity.getExternalFilesDir(null), "updates-ui").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
