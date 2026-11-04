package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class SelectedFeaturesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionWindow() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun bookmarkCreationDoesNotConnectAndQuickConnectUsesSavedEntry() {
        val fixture = UiFixture()
        fixture.form.value = fixture.form.value.copy(host = "voice.example.com", nickname = "Fixture")
        rule.setContent { fixture.Render() }
        openMenu("Server bookmarks")
        rule.onNodeWithText("Add bookmark").performClick()
        rule.onNodeWithText("Bookmark name").performTextInput("My fixture")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("My fixture").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, fixture.bookmarkConnections.size)
            assertEquals(0, fixture.connects)
        }
        rule.onNodeWithContentDescription("Connect to My fixture").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("My fixture", fixture.bookmarkConnections.single().name) }
    }

    @Test fun bookmarkPasswordOptOutAndConfirmedDeletionAffectOnlyBookmark() {
        val fixture = UiFixture()
        fixture.bookmarks.value =
            BookmarkState(
                listOf(
                    ServerBookmark(
                        "fixture",
                        1,
                        "Saved fixture",
                        "voice.example.com",
                        rememberPasswords = true,
                        password = "fixture-secret",
                        channelPassword = "fixture-channel",
                    ),
                ),
                loading = false,
            )
        fixture.form.value = fixture.form.value.copy(host = "other.example.com", password = "independent-form-secret")
        rule.setContent { fixture.Render() }
        openMenu("Server bookmarks")
        rule.onNodeWithContentDescription("Options for Saved fixture").performClick()
        rule.onNodeWithText("Edit").performScrollTo().performClick()
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle { assertEquals("", fixture.bookmarks.value.entries.single().password) }
        rule.onNodeWithContentDescription("Options for Saved fixture").performClick()
        rule.onNodeWithText("Delete").performScrollTo().performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, fixture.bookmarks.value.entries.size) }
        rule.onNodeWithContentDescription("Options for Saved fixture").performClick()
        rule.onNodeWithText("Delete").performScrollTo().performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle {
            assertTrue(fixture.bookmarks.value.entries.isEmpty())
            assertEquals("independent-form-secret", fixture.form.value.password)
        }
    }

    @Test fun connectedBookmarksRequireExplicitDisconnectBeforeSwitching() {
        val fixture = UiFixture(connected = true)
        fixture.bookmarks.value = BookmarkState(listOf(ServerBookmark("fixture", 1, "Saved fixture", "voice.example.com")), loading = false)
        rule.setContent { fixture.Render() }
        openMenu("Server bookmarks")
        rule.onNodeWithContentDescription("Connect to Saved fixture").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Disconnect from the current server before connecting to another bookmark.").assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.bookmarkConnections.isEmpty()) }
    }

    @Test fun awayMessageAppliesAndShowsServerResultWithoutStoppingVoice() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openMenu("Away and status")
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("Away message (up to 256 UTF-8 bytes)").performTextInput("稍后回来")
        rule.onNodeWithText("Apply status").performClick()
        rule.onNodeWithText("Server accepted the current status.").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(true to "稍后回来", fixture.awayRequests.single())
            assertFalse(fixture.transmission.any { it })
        }
    }

    @Test fun channelDetailsAreAbsentFromTheRosterAndSearch() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Information for Lobby").assertDoesNotExist()
        openMenu("Find channels and users")
        rule.onNodeWithText("Channel information").assertDoesNotExist()
        rule.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun userSearchJoinsTheirChannelAndProtectedSearchResultUsesPasswordDialog() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openMenu("Find channels and users")
        rule.onNodeWithText("Channel, topic or nickname").performTextInput("MORGAN")
        rule.onNodeWithText("Channel, topic or nickname").performImeAction()
        rule.onNodeWithText("Join this user’s channel").performClick()
        rule.runOnIdle { assertEquals(listOf(2 to ""), fixture.joins) }
        openMenu("Find channels and users")
        rule.onNodeWithText("Channel, topic or nickname").performTextInput("Private")
        rule.onNodeWithText("Channel, topic or nickname").performImeAction()
        rule.onNodeWithText("Join").performClick()
        rule.onNodeWithText("Channel password").performTextInput("fixture-password")
        rule.onNodeWithText("Join").performClick()
        rule.runOnIdle { assertEquals(3 to "fixture-password", fixture.joins.last()) }
    }

    @Test fun chineseLargeTextBookmarkEditorAndSearchKeepActionsVisible() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(fontScale = 1.8f, locale = "zh-CN") }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("查找频道与用户").performScrollTo().performClick()
        rule.onNodeWithText("频道、主题或昵称").performTextInput("Design")
        rule.onNodeWithText("频道、主题或昵称").performImeAction()
        saveDialog("search-zh-large")
        rule.onNodeWithText("频道信息").assertDoesNotExist()
        rule.onNodeWithText("关闭").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("服务器收藏").performScrollTo().performClick()
        rule.onNodeWithText("新增收藏").performClick()
        rule.onNodeWithText("收藏名称").performTextInput("大字体收藏")
        rule.onNodeWithText("更多设置").performScrollTo().performClick()
        rule.onNodeWithText("默认频道 ID（可选）").performScrollTo().performTextInput("3")
        rule.onNodeWithText("新增收藏").assertIsDisplayed()
        rule.onNodeWithText("保存").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
        saveDialog("bookmark-editor-zh-large")
    }

    private fun openMenu(item: String) {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText(item).performScrollTo().performClick()
    }

    private fun saveDialog(name: String) {
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(rule.activity.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
