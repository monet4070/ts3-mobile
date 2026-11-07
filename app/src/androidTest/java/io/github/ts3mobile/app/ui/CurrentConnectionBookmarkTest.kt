package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.ts3mobile.app.bookmarks.BookmarkCodec
import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class CurrentConnectionBookmarkTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun savingCurrentConnectionUsesLiveEndpointNicknameAndChannelWithoutPasswords() {
        val fixture = liveFixture()
        fixture.form.value = fixture.form.value.copy(host = "edited.example.com", nickname = "Edited", password = "fixture-secret")
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Save current connection").performClick()
        rule.onNodeWithText("Bookmark name").performTextInput("Live bookmark")
        save()
        rule.runOnIdle {
            val saved = fixture.bookmarks.value.entries.single()
            assertEquals("live.example.com", saved.host)
            assertEquals(10000, saved.port)
            assertEquals("Active nickname", saved.nickname)
            assertEquals(1, saved.defaultChannelId)
            assertEquals("", saved.password)
            assertEquals("", saved.channelPassword)
            assertFalse(saved.rememberPasswords)
            assertTrue(fixture.bookmarkConnections.isEmpty())
        }
    }

    @Test fun namePickerDistinguishesPathsAndSavesTheSelectedChannelId() {
        val fixture = liveFixture()
        fixture.state.value =
            fixture.state.value.copy(
                snapshot =
                    fixture.state.value.snapshot.copy(
                        channels = fixture.state.value.snapshot.channels.map { if (it.id == 4) it.copy(name = "Lobby") else it },
                    ),
            )
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Save current connection").performClick()
        rule.onNodeWithText("Bookmark name").performTextInput("Named channel")
        rule.onNodeWithText("Choose default channel").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("Lobby (#1)").assertIsDisplayed()
        rule.onNodeWithText("Design studio / Lobby (#4)").assertIsDisplayed()
        rule.onNodeWithTag("bookmark_channel_4").performClick()
        save()
        rule.runOnIdle { assertEquals(4, fixture.bookmarks.value.entries.single().defaultChannelId) }
    }

    @Test fun editingAnotherServerNeverOffersTheCurrentServersChannels() {
        val fixture = liveFixture()
        fixture.bookmarks.value = BookmarkState(listOf(ServerBookmark("other", 1, "Other", "other.example.com")), loading = false)
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithContentDescription("Options for Other").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Choose default channel").assertDoesNotExist()
        rule.onNodeWithText("Use current channel").assertDoesNotExist()
    }

    @Test fun replacingTheManualSessionRetainsDraftAndBlocksCapturedSelection() {
        val fixture = liveFixture()
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Save current connection").performClick()
        rule.onNodeWithText("Bookmark name").performTextInput("Unsaved name")
        rule.runOnIdle {
            fixture.state.value =
                fixture.state.value.copy(
                    bookmarkConnection = fixture.state.value.bookmarkConnection!!.copy(token = "replacement"),
                )
        }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Unsaved name").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Use current channel").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        save()
        rule.runOnIdle { assertEquals("Unsaved name", fixture.bookmarks.value.entries.single().name) }
    }

    @Test fun deletingTheChosenChannelBlocksSaveUntilItIsCleared() {
        val fixture = liveFixture()
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Save current connection").performClick()
        rule.onNodeWithText("Bookmark name").performTextInput("Without channel")
        rule.runOnIdle {
            fixture.state.value =
                fixture.state.value.copy(
                    snapshot =
                        fixture.state.value.snapshot.copy(
                            channels = fixture.state.value.snapshot.channels.filterNot { it.id == 1 },
                        ),
                )
        }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Clear default channel").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        save()
        rule.runOnIdle { assertEquals(null, fixture.bookmarks.value.entries.single().defaultChannelId) }
    }

    @Test fun disconnectedDraftWithNoCurrentChannelCanBeExplicitlySavedOffline() {
        val fixture = liveFixture()
        fixture.state.value = fixture.state.value.copy(snapshot = fixture.state.value.snapshot.copy(ownClientId = null))
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Save current connection").performClick()
        rule.onNodeWithText("Bookmark name").performTextInput("Offline draft")
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(status = ConnectionStatus(ConnectionPhase.DISCONNECTED)) }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Clear default channel").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        save()
        rule.runOnIdle { assertEquals(null, fixture.bookmarks.value.entries.single().defaultChannelId) }
    }

    @Test fun anotherWritersRevisionCannotDismissOrOverwriteAnUnsavedDraft() {
        val fixture = liveFixture()
        val original = ServerBookmark("fixture", 1, "Original", "live.example.com", port = 10000)
        fixture.bookmarks.value = BookmarkState(listOf(original), loading = false)
        rule.setContent {
            fixture.Render(saveBookmark = { requested ->
                runCatching { BookmarkCodec.save(fixture.bookmarks.value.entries, requested) }
                    .onSuccess { fixture.bookmarks.value = BookmarkState(it, loading = false) }
                    .onFailure { fixture.bookmarks.value = fixture.bookmarks.value.copy(failed = true) }
            })
        }
        openBookmarks()
        rule.onNodeWithContentDescription("Options for Original").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Bookmark name").performTextReplacement("Unsaved draft")
        rule.runOnIdle {
            fixture.bookmarks.value = BookmarkState(listOf(original.copy(revision = 2, name = "Other writer")), loading = false)
        }
        save()
        rule.onNodeWithText("Unsaved draft").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals("Other writer", fixture.bookmarks.value.entries.single().name) }
    }

    @Test fun chineseLargeTextPickerKeepsChoiceAndCloseAvailableInTheDarkPalette() {
        val fixture = liveFixture()
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f, dark = true) }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("服务器收藏").performClick()
        rule.onNodeWithText("保存当前连接").performClick()
        rule.onNodeWithText("选择默认频道").performScrollTo().performClick()
        rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
            .performScrollToNode(hasTestTag("bookmark_channel_3"))
        rule.onNodeWithTag("bookmark_channel_3").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed()
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(rule.activity.filesDir, "bookmark-picker-zh-large-dark.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        rule.onNodeWithTag("bookmark_channel_3").performClick()
        rule.runOnIdle { assertTrue(fixture.joins.isEmpty()) }
        rule.onNodeWithText("保存").assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsDisplayed()
    }

    private fun liveFixture() =
        UiFixture(connected = true).apply {
            state.value =
                state.value.copy(
                    bookmarkConnection = BookmarkConnection("live-session", "live.example.com", 10000, "Active nickname"),
                )
        }

    private fun openBookmarks() {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Server bookmarks").performClick()
    }

    private fun save() = rule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick) { it() }
}
