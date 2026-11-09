package io.github.ts3mobile.app.updates

import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.ui.UiFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class UpdateEntryTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun disconnectedMenuOffersTheUpdatePageWithoutConnecting() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Check for updates").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.updateChecks)
            assertEquals(0, fixture.connects)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun installedMetadataAndThePrivateActivityMatchTheActualPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = installedAppVersion(context)
        assertEquals(context.packageName, info.applicationId)
        assertTrue(info.code > 0)
        assertTrue(info.certificates.isNotEmpty() && info.certificates.all { it.matches(Regex("[0-9a-f]{64}")) })
        val activities = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES).activities
        assertFalse(activities.orEmpty().single { it.name == UpdatesActivity::class.java.name }.exported)
    }
}
