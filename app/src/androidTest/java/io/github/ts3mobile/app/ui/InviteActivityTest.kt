package io.github.ts3mobile.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.invite.ServerInvite
import io.github.ts3mobile.app.settings.ConnectionSettingsVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InviteActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun coldLinkAndRecreationOnlyPreviewAndDoNotRememberTheInvite() {
        val vault = ConnectionSettingsVault(context)
        val originalForm = runBlocking { vault.read() }
        val link = linkIntent("cold.example.invalid?cid=7&password=synthetic-ignored-password")
        ActivityScenario.launch<MainActivity>(link).use { scenario ->
            assertPreview("cold.example.invalid:9987")
            rule.onNodeWithText("synthetic-ignored-password", substring = true).assertDoesNotExist()
            scenario.recreate()
            assertPreview("cold.example.invalid:9987")
            rule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
            rule.onNode(isDialog()).assertDoesNotExist()
            rule.onNodeWithText(context.getString(R.string.invite_paste)).performScrollTo().assertIsDisplayed()
        }
        assertEquals(originalForm, runBlocking { vault.read() })
    }

    @Test fun manualPasteAndWarmIntentUseTheSamePreview() {
        val launch = Intent(context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            rule.onNodeWithText(context.getString(R.string.invite_paste)).performScrollTo().performClick()
            rule.onNodeWithText(context.getString(R.string.invite_link)).performTextReplacement(linkText("[2001:db8::2]:9999?cid=9"))
            rule.onNodeWithText(context.getString(R.string.invite_preview)).performClick()
            assertPreview("[2001:db8::2]:9999")
            rule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
            val warmLink =
                Intent(Intent.ACTION_VIEW, Uri.parse(linkText("warm.example.invalid?port=9998")))
                    .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            scenario.onActivity { it.startActivity(warmLink) }
            try {
                rule.waitUntil(5_000) {
                    runCatching { assertPreview("warm.example.invalid:9998") }.isSuccess
                }
            } finally {
                scenario.onActivity { it.intent = launch }
            }
        }
    }

    private fun assertPreview(endpoint: String) {
        rule.onNode(isDialog()).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.invite_target, endpoint)).performScrollTo().assertIsDisplayed()
    }

    private fun linkIntent(target: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(linkText(target)), context, MainActivity::class.java)

    private fun linkText(target: String): String = "${ServerInvite.SCHEME}://$target"
}
