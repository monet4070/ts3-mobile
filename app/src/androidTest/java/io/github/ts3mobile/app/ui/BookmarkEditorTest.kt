package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BookmarkEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionWindow() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun newBookmarkStartsWithBasicFieldsAndNeverCopiesFormPasswords() {
        val fixture = UiFixture()
        fixture.form.value = fixture.form.value.copy(password = "independent-fixture-password")
        rule.setContent { fixture.Render() }
        openNewBookmark()
        rule.onNodeWithText("Default channel ID (optional)").assertDoesNotExist()
        rule.onNodeWithText("Save passwords").assertDoesNotExist()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Bookmark name").performTextInput("My server")
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle {
            val saved = fixture.bookmarks.value.entries.single()
            assertEquals(fixture.form.value.host, saved.host)
            assertEquals("", saved.password)
            assertFalse(saved.rememberPasswords)
            assertTrue(fixture.bookmarkConnections.isEmpty())
        }
    }

    @Test fun optionalSettingsCanBeCollapsedWithoutLosingSavedValues() {
        val fixture = UiFixture()
        val original = ServerBookmark("fixture", 1, "Studio", "voice.example.com", defaultChannelId = 42)
        fixture.bookmarks.value = BookmarkState(listOf(original), loading = false)
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Choose a saved server").performClick()
        rule.onNodeWithContentDescription("Options for Studio").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Default channel ID (optional)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("More settings").performScrollTo().performClick()
        rule.onNodeWithText("Default channel ID (optional)").assertDoesNotExist()
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle { assertEquals(42, fixture.bookmarks.value.entries.single().defaultChannelId) }
    }

    @Test fun saveFailureKeepsTheDraftVisibleAndAllowsRetry() {
        val fixture = UiFixture()
        var attempts = 0
        rule.setContent {
            fixture.Render(saveBookmark = {
                attempts++
                fixture.bookmarks.value = fixture.bookmarks.value.copy(failed = true)
            })
        }
        openNewBookmark()
        rule.onNodeWithText("Bookmark name").performTextInput("Unsaved draft")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Bookmark operation failed. Saved bookmarks were kept. Please try again.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Unsaved draft").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle {
            assertEquals(2, attempts)
            assertTrue(fixture.bookmarks.value.entries.isEmpty())
            assertTrue(fixture.bookmarkConnections.isEmpty())
        }
    }

    @Test fun invalidPortCannotBeSavedAndCancelReturnsToTheUnchangedList() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        openNewBookmark()
        rule.onNodeWithText("Bookmark name").performTextInput("Draft")
        rule.onNode(hasText("Port") and hasAnyAncestor(isDialog())).performTextReplacement("70000")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("No saved servers yet").assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.bookmarks.value.entries.isEmpty()) }
    }

    @Test fun chineseBasicEditorPreview() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.onNodeWithText("选择收藏服务器").performClick()
        rule.onNodeWithText("新增收藏").performClick()
        rule.onNodeWithText("收藏名称").performTextInput("晚间语音")
        rule.onNodeWithText("收藏名称").performImeAction()
        save("bookmark-editor-zh")
    }

    @Test fun largeChineseOptionalFieldsKeepSaveAndCancelAboveTheKeyboard() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f, dark = true) }
        rule.onNodeWithText("选择收藏服务器").performScrollTo().performClick()
        rule.onNodeWithText("新增收藏").performClick()
        rule.onNodeWithText("收藏名称").performTextInput("大字体收藏")
        rule.onNodeWithText("更多设置").performScrollTo().performClick()
        rule.onNodeWithText("默认频道 ID（可选）").performScrollTo().performTextInput("42")
        rule.onNodeWithText("保存").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
        save("bookmark-editor-dark-zh-large-ime")
        rule.onNodeWithText("默认频道 ID（可选）").performImeAction()
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle {
            assertEquals(42, fixture.bookmarks.value.entries.single().defaultChannelId)
            assertTrue(fixture.bookmarkConnections.isEmpty())
        }
    }

    private fun openNewBookmark() {
        rule.onNodeWithText("Choose a saved server").performClick()
        rule.onNodeWithText("Add bookmark").performClick()
    }

    private fun save(name: String) {
        val output = File(rule.activity.getExternalFilesDir(null), "bookmark-ui").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            check(rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
