package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.bookmarks.BookmarkConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChannelJoinActionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun visibleAccessibleActionJoinsAnUnprotectedChannel() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        reveal("Join channel Design studio")
        rule.onNodeWithContentDescription("Join channel Design studio").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(listOf(2 to ""), fixture.joins) }
        rule.onNodeWithContentDescription("Join channel Lobby").assertDoesNotExist()
    }

    @Test fun protectedActionUsesTheExistingPasswordDialog() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        reveal("Join channel Private room")
        rule.onNodeWithContentDescription("Join channel Private room").performClick()
        rule.onNodeWithText("Channel password").performTextInput("fixture-channel")
        rule.onNode(hasText("Join") and hasAnyAncestor(isDialog())).performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(listOf(3 to "fixture-channel"), fixture.joins) }
    }

    @Test fun replacingConnectionDismissesPasswordTargetWithoutJoining() {
        val fixture = UiFixture(connected = true)
        val connection = BookmarkConnection("first", "voice.example.com", 9987, "Fixture")
        fixture.state.value = fixture.state.value.copy(bookmarkConnection = connection)
        rule.setContent { fixture.Render() }
        reveal("Join channel Private room")
        rule.onNodeWithContentDescription("Join channel Private room").performClick()
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(bookmarkConnection = connection.copy(token = "second")) }
        rule.onNodeWithText("Channel password").assertDoesNotExist()
        rule.runOnIdle { assertTrue(fixture.joins.isEmpty()) }
    }

    @Test fun chineseLargeTextVisibleJoinRemainsAvailableOnANarrowScreen() {
        val fixture = UiFixture(connected = true)
        rule.setContent { Box(Modifier.width(320.dp)) { fixture.Render(locale = "zh-CN", fontScale = 1.8f) } }
        reveal("加入频道Design studio")
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        File(rule.activity.filesDir, "channel-join-zh-large.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        rule.onNodeWithContentDescription("加入频道Design studio").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(listOf(2 to ""), fixture.joins) }
    }

    private fun reveal(description: String) {
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(description))
        rule.onNodeWithContentDescription(description).performScrollTo()
    }
}
