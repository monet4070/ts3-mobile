package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.ts3mobile.app.bookmarks.BookmarkState
import io.github.ts3mobile.app.bookmarks.ServerBookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class BookmarkListTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val studio = ServerBookmark("studio", 1, "Studio", "voice.example.com", nickname = "Alex")
    private val weekend = ServerBookmark("weekend", 1, "Weekend", "weekend.example.com", nickname = "Sam")

    @Test fun cardMenusKeepSecondaryActionsHiddenAndEditTheSelectedEntry() {
        val fixture = withEntries(studio, weekend)
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Saved: 2").assertIsDisplayed()
        listOf("Fill form", "Edit", "Delete").forEach { rule.onNodeWithText(it).assertDoesNotExist() }
        rule.onNodeWithContentDescription("Options for Weekend").performScrollTo().performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Bookmark name").performTextReplacement("Weekend team")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Weekend team").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(studio, fixture.bookmarks.value.entries.first())
            assertEquals("Weekend team", fixture.bookmarks.value.entries.last().name)
            assertTrue(fixture.bookmarkConnections.isEmpty())
        }
    }

    @Test fun primaryConnectUsesTheChosenEntryAndDismissesTheList() {
        val fixture = withEntries(studio, weekend)
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithContentDescription("Connect to Weekend").performScrollTo().performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(weekend), fixture.bookmarkConnections) }
    }

    @Test fun fillFormInTheMenuDoesNotConnect() {
        val fixture = withEntries(studio, weekend)
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithContentDescription("Options for Weekend").performScrollTo().performClick()
        rule.onNodeWithText("Fill form").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(weekend.host, fixture.form.value.host)
            assertEquals(weekend.nickname, fixture.form.value.nickname)
            assertTrue(fixture.bookmarkConnections.isEmpty())
            assertEquals(0, fixture.connects)
        }
    }

    @Test fun deleteRequiresConfirmationAndRemovesOnlyTheChosenEntry() {
        val fixture = withEntries(studio, weekend)
        rule.setContent { fixture.Render() }
        openBookmarks()
        deleteWeekend()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(listOf(studio, weekend), fixture.bookmarks.value.entries) }
        deleteWeekend()
        rule.onNodeWithText("Delete").performClick()
        rule.runOnIdle { assertEquals(listOf(studio), fixture.bookmarks.value.entries) }
        rule.onNodeWithText("Saved: 1").assertIsDisplayed()
    }

    @Test fun loadingAndBusyStatesDisableEntryActionsAndAdding() {
        val fixture = withEntries(studio)
        fixture.bookmarks.value = fixture.bookmarks.value.copy(loading = true)
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Loading bookmarks…").assertIsDisplayed()
        assertActionsDisabled()
        rule.runOnIdle { fixture.bookmarks.value = fixture.bookmarks.value.copy(loading = false, busy = true) }
        assertActionsDisabled()
        rule.runOnIdle { fixture.bookmarks.value = fixture.bookmarks.value.copy(busy = false) }
        rule.onNodeWithContentDescription("Connect to Studio").assertIsEnabled()
        rule.onNodeWithText("Add bookmark").assertIsEnabled()
    }

    @Test fun failedLoadingShowsRecoveryWithoutClaimingTheListIsEmpty() {
        val fixture = UiFixture()
        fixture.bookmarks.value = BookmarkState(loading = false, failed = true)
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Reload bookmarks").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("No saved servers yet").assertDoesNotExist()
        rule.runOnIdle { fixture.bookmarks.value = BookmarkState(listOf(studio), loading = false) }
        rule.onNodeWithText("Reload bookmarks").assertDoesNotExist()
        rule.onNodeWithText("Studio").assertIsDisplayed()
    }

    @Test fun connectedSessionDisablesFillAndConnectButKeepsEditingAvailable() {
        val fixture = UiFixture(connected = true)
        fixture.bookmarks.value = BookmarkState(listOf(studio), loading = false)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Server bookmarks").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Connect to Studio").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Options for Studio").performClick()
        rule.onNodeWithText("Fill form").assertIsNotEnabled()
        rule.onNodeWithText("Edit").assertIsEnabled()
    }

    @Test fun longListsScrollWhileTheAddActionStaysVisibleAndTheLimitIsExplained() {
        val fixture = withEntries(*(1..50).map { studio.copy(id = "fixture-$it", name = "Server $it") }.toTypedArray())
        rule.setContent { fixture.Render() }
        openBookmarks()
        rule.onNodeWithText("Add bookmark").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("Server 50").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Bookmark limit: 50").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Add bookmark").assertIsDisplayed()
        rule.onNodeWithContentDescription("Options for Server 50").performScrollTo().performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Add bookmark").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun warmChineseListPreview() = preview(dark = false)

    @Test fun warmChineseDarkListPreview() = preview(dark = true)

    @Test fun chineseLargeTextKeepsLongNamesAndActionsReachable() {
        val entry = studio.copy(name = "周末一起打游戏的朋友们", nickname = "一个很长的测试昵称")
        val fixture = withEntries(entry, weekend)
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f) }
        rule.onNodeWithText("选择收藏服务器").performScrollTo().performClick()
        rule.onNodeWithText(entry.name).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("连接到 ${entry.name}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("新增收藏").assertIsDisplayed()
        save("bookmarks-zh-large")
        rule.onNodeWithContentDescription("${entry.name} 的收藏选项").performScrollTo().performClick()
        rule.onNodeWithText("编辑").assertIsDisplayed().performClick()
        rule.onNodeWithText("保存").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test fun emptyListHasOneClearAddAction() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.onNodeWithText("选择收藏服务器").performClick()
        rule.onNodeWithText("还没有收藏的服务器").assertIsDisplayed()
        rule.onNodeWithText("新增收藏").assertIsDisplayed().assertIsEnabled()
        save("bookmarks-empty-zh")
    }

    private fun preview(dark: Boolean) {
        val fixture = withEntries(studio.copy(name = "晚间语音"), weekend.copy(name = "周末小队"))
        rule.setContent { fixture.Render(dark = dark, locale = "zh-CN") }
        rule.onNodeWithText("选择收藏服务器").performClick()
        rule.onNodeWithContentDescription("连接到 晚间语音").assertIsDisplayed()
        save(if (dark) "bookmarks-dark-zh" else "bookmarks-light-zh")
        rule.onNodeWithContentDescription("晚间语音 的收藏选项").performClick()
        rule.onNodeWithText("编辑").assertIsDisplayed()
        save(if (dark) "bookmark-menu-dark-zh" else "bookmark-menu-light-zh")
    }

    private fun withEntries(vararg entries: ServerBookmark) =
        UiFixture().apply {
            bookmarks.value = BookmarkState(entries.toList(), loading = false)
        }

    private fun openBookmarks() {
        rule.onNodeWithText("Choose a saved server").performScrollTo().performClick()
    }

    private fun deleteWeekend() {
        rule.onNodeWithContentDescription("Options for Weekend").performScrollTo().performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete bookmark?").assertIsDisplayed()
    }

    private fun assertActionsDisabled() {
        rule.onNodeWithContentDescription("Connect to Studio").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Options for Studio").assertIsNotEnabled()
        rule.onNodeWithText("Add bookmark").assertIsNotEnabled()
    }

    private fun save(name: String) {
        val output = File(rule.activity.getExternalFilesDir(null), "bookmark-ui").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            check(rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
