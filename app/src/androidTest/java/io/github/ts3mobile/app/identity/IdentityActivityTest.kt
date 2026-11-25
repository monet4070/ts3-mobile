package io.github.ts3mobile.app.identity

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import io.github.ts3mobile.app.Ts3MobileApplication
import io.github.ts3mobile.app.identity.documents.BackupDocument
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

class IdentityActivityTest {
    @get:Rule val rule = createAndroidComposeRule<IdentityManagementActivity>()

    @Test fun rotationDoesNotSavePasswordAndVisitingDoesNotGenerateOrReplaceIdentity() =
        runBlocking {
            val identities = (rule.activity.application as Ts3MobileApplication).identities
            val before = identities.snapshot()
            rule.waitUntil(
                5_000,
            ) { rule.onAllNodes(androidx.compose.ui.test.hasText("Reading identity summary…")).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("Backup passphrase").performScrollTo().performTextInput("synthetic rotation secret")
            rule.activityRule.scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            rule.activityRule.scenario.recreate()
            rule.onNodeWithText("Backup passphrase").performScrollTo().assertTextEquals("Backup passphrase", "")
            rule.onNodeWithText("Export encrypted backup…").performScrollTo().assertIsDisplayed()
            assertEquals(before, identities.snapshot())
        }

    @Test fun desktopReviewSurvivesRotationButClosingWithoutConfirmationDiscardsIt() =
        runBlocking {
            val identities = (rule.activity.application as Ts3MobileApplication).identities
            val before = identities.snapshot()
            val material = Ts3IdentityCodec.generate(0)
            val expected = Ts3IdentityCodec.inspect(material)
            val bytes = material.toByteArray()
            try {
                lateinit var controller: IdentityRestoreController
                val scenario = rule.activityRule.scenario
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[IdentityManagementViewModel::class.java]
                    controller = model.restores
                    model.importDesktop()
                    val request = checkNotNull(controller.state.value.pickerRequest)
                    assertTrue(controller.claimPickerRequest(request))
                    controller.picked(
                        object : BackupDocument {
                            override fun openRead() = ByteArrayInputStream(bytes)

                            override fun openWrite() = error("Desktop import must not write to its source")
                        },
                    )
                }
                rule.waitUntil(5_000) { controller.state.value.status == IdentityRestoreStatus.PREVIEW }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val restore = ViewModelProvider(activity)[IdentityManagementViewModel::class.java].restores.state.value
                    assertEquals(expected, restore.preview?.proposed)
                    assertFalse(restore.pickerOutstanding)
                    assertEquals(IdentityRestoreStatus.PREVIEW, restore.status)
                }
                assertEquals(before, identities.snapshot())
                scenario.close()
                assertNull(controller.state.value.preview)
                assertFalse(controller.state.value.active)
                assertEquals(before, identities.snapshot())
            } finally {
                bytes.fill(0)
            }
        }
}
