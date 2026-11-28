package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.ts3mobile.app.MainViewModel
import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.protocol.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookmarkCredentialsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val original =
        ServerBookmark(
            id = "fixture",
            revision = 1,
            name = "Studio",
            host = "voice.example.com",
            nickname = "Alex",
            defaultChannelId = 2,
            rememberPasswords = true,
            password = "fixture-server",
            channelPassword = "fixture-channel",
        )

    @Test fun changingHostNeverSubmitsOldPasswordsEvenWhenTheSameChannelIdIsEnteredAgain() {
        val fixture = withBookmark()
        rule.setContent { fixture.Render() }
        openEdit()
        field("Server address").performTextReplacement("other.example.com")
        rule.onNodeWithText("Passwords for the previous target were cleared. Re-enter them if needed.")
            .performScrollTo().assertIsDisplayed()
        field("Default channel ID (optional)").performScrollTo().performTextReplacement("2")
        save()
        assertReopenedConnection(fixture, ServerConfig("other.example.com", nickname = "Alex", defaultChannelId = 2))
    }

    @Test fun invalidPortAndReturnToOriginalInTheLargeFontEditorDoesNotReviveCredentials() {
        val fixture = withBookmark()
        rule.setContent { fixture.Render(fontScale = 1.8f) }
        openEdit()
        field("Port").performScrollTo().performTextReplacement("65536")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        field("Port").performScrollTo().performTextReplacement(original.port.toString())
        save()
        assertReopenedConnection(fixture, ServerConfig(original.host, nickname = "Alex"))
    }

    @Test fun normalizedTargetsRetainBothCredentialsButManualChannelChangesClearOnlyItsPassword() {
        val fixture = withBookmark()
        rule.setContent { fixture.Render() }
        openEdit()
        field("Server address").performTextReplacement(" VOICE.EXAMPLE.COM ")
        field("Port").performTextReplacement("09987")
        field("Default channel ID (optional)").performScrollTo().performTextReplacement("02")
        save()
        val normalized = original.toConfig().copy(host = "VOICE.EXAMPLE.COM")
        assertReopenedConnection(fixture, normalized)

        openEdit()
        field("Default channel ID (optional)").performScrollTo().performTextReplacement("3")
        save()
        assertReopenedConnection(fixture, normalized.copy(defaultChannelId = 3, channelPassword = ""))
    }

    @Test fun stalePickerSelectionCannotOverwriteTheOriginalAndUsingCurrentChannelClearsOnlyItsPassword() {
        val fixture = withBookmark(connected = true)
        val connection = BookmarkConnection("first-session", original.host, original.port, original.nickname)
        fixture.state.value = fixture.state.value.copy(bookmarkConnection = connection)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Server bookmarks").performClick()
        editStudio()
        rule.onNodeWithText("Choose default channel").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("bookmark_channel_3").performClick()
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(bookmarkConnection = connection.copy(token = "second-session")) }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(original, fixture.bookmarks.value.entries.single()) }
        rule.onNodeWithText("Use current channel").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        save()
        rule.runOnIdle {
            val saved = fixture.bookmarks.value.entries.single()
            assertEquals(1, saved.defaultChannelId)
            assertEquals(original.password, saved.password)
            assertEquals("", saved.channelPassword)
        }
    }

    @Test fun failedSaveThenCancelKeepsTheOriginalTargetAndCredentials() {
        val fixture = withBookmark()
        rule.setContent { fixture.Render(saveBookmark = { fixture.bookmarks.value = fixture.bookmarks.value.copy(failed = true) }) }
        openEdit()
        field("Port").performTextReplacement("9988")
        save()
        rule.onNodeWithText("Bookmark operation failed. Saved bookmarks were kept. Please try again.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle {
            assertEquals(original, fixture.bookmarks.value.entries.single())
            assertTrue(fixture.bookmarkConnections.isEmpty())
        }
        assertReopenedConnection(fixture, original.toConfig())
    }

    private fun withBookmark(connected: Boolean = false) =
        UiFixture(connected).apply { bookmarks.value = BookmarkState(listOf(original), loading = false) }

    private fun openEdit() {
        rule.onNodeWithText("Choose a saved server").performScrollTo().performClick()
        editStudio()
    }

    private fun editStudio() {
        rule.onNodeWithContentDescription("Options for Studio").performScrollTo().performClick()
        rule.onNodeWithText("Edit").performClick()
    }

    private fun field(label: String) = rule.onNode(hasText(label) and hasSetTextAction() and hasAnyAncestor(isDialog()))

    private fun save() = rule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick) { it() }

    private fun assertReopenedConnection(
        fixture: UiFixture,
        expected: ServerConfig,
    ) {
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Choose a saved server").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Connect to Studio").performScrollTo().performClick()
        rule.runOnIdle {
            val viewModels = ViewModelStore()
            try {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(rule.activity.application)
                val viewModel = ViewModelProvider(viewModels, factory)[MainViewModel::class.java]
                viewModel.applyBookmark(fixture.bookmarkConnections.last())
                assertEquals(expected, viewModel.submit())
            } finally {
                viewModels.clear()
            }
        }
    }
}
