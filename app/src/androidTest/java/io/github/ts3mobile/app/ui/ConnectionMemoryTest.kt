package io.github.ts3mobile.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.settings.ConnectionSettingsVault
import io.github.ts3mobile.app.settings.RememberedConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Run on a disposable emulator or isolated test package; only fixture credentials are written. */
class ConnectionMemoryTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun ActivityScenario<MainActivity>.text(id: Int): String {
        lateinit var value: String
        onActivity { value = it.getString(id) }
        return value
    }

    @Test fun editedDetailsAreSavedAndFilledAgainWithoutAConnectionAttempt(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = RememberedConnection("edited.example.com", "10012", "Edited Alex", "edited-fixture-password")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.waitForIdle()
                rule.onNodeWithText(scenario.text(R.string.field_server_address)).performScrollTo().performTextReplacement(fixture.host)
                rule.onNodeWithText(scenario.text(R.string.field_port)).performScrollTo().performTextReplacement(fixture.port)
                rule.onNodeWithText(scenario.text(R.string.field_nickname)).performScrollTo().performTextReplacement(fixture.nickname)
                rule.onNodeWithText(
                    scenario.text(R.string.field_server_password),
                ).performScrollTo().performTextReplacement(fixture.password)
                rule.waitUntil(5_000L) { runBlocking { ConnectionSettingsVault(context).read() == fixture } }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.waitUntil(5_000L) {
                    rule.onAllNodes(androidx.compose.ui.test.hasText(fixture.nickname)).fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithContentDescription(scenario.text(R.string.action_show_password)).performScrollTo().performClick()
                rule.onNodeWithText(
                    fixture.password,
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(fixture.password)))
                rule.onNodeWithText(
                    fixture.port,
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(fixture.port)))
            }
        }

    @Test fun encryptedDetailsSurviveANewVaultAndANewActivityWithoutConnecting() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = RememberedConnection("saved.example.com", "10011", "Remembered Alex", "fixture-only-password")
            ConnectionSettingsVault(context).write(fixture)
            assertEquals(fixture, ConnectionSettingsVault(context).read())
            val file = File(context.filesDir, "datastore/ts3_connection_settings.preferences_pb")
            val disk = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(fixture.host))
            assertFalse(disk.contains(fixture.nickname))
            assertFalse(disk.contains(fixture.password))
            repeat(2) {
                ActivityScenario.launch(MainActivity::class.java).use {
                    rule.waitUntil(5_000L) {
                        rule.onAllNodes(androidx.compose.ui.test.hasText(fixture.nickname)).fetchSemanticsNodes().isNotEmpty()
                    }
                    rule.onNodeWithText(
                        fixture.host,
                    ).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(fixture.host)))
                    rule.onNodeWithText(
                        fixture.nickname,
                    ).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(fixture.nickname)))
                }
            }
        }
}
