package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SearchResultLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun userNameChannelAndJoinShareOneStructuredRow() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Find channels and users").performScrollTo().performClick()
        rule.onNodeWithText("Channel, topic or nickname").performTextInput("Morgan")
        rule.onNodeWithText("Channel, topic or nickname").performImeAction()
        val name = rule.onNode(hasText("Morgan") and hasAnyAncestor(hasTestTag("discovery_user_4"))).fetchSemanticsNode().boundsInRoot
        val channel =
            rule.onNode(
                hasText("Design studio") and hasAnyAncestor(hasTestTag("discovery_user_4")),
            ).fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithContentDescription("Join this user’s channel").fetchSemanticsNode().boundsInRoot
        assertTrue(channel.top >= name.bottom)
        assertTrue(action.left > name.left && action.center.y > name.top && action.center.y < channel.bottom)
        saveDialog("search-user")
        rule.onNodeWithContentDescription("Join this user’s channel").performClick()
        rule.runOnIdle { assertEquals(listOf(2 to ""), fixture.joins) }
    }

    @Test fun currentChannelHasAnExplicitStatusAndLargeTextActionsRemainVisible() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f, dark = true) }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("查找频道与用户").performScrollTo().performClick()
        rule.onNodeWithTag("discovery_channel_1").assertIsDisplayed()
        rule.onNode(hasContentDescription("当前频道") and hasAnyAncestor(hasTestTag("discovery_channel_1"))).assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed()
        saveDialog("search-zh-dark-large")
    }

    private fun saveDialog(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "ui-hierarchy/" + name + ".png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            check(rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
