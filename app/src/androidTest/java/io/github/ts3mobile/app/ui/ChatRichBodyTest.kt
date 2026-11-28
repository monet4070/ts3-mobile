package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import io.github.ts3mobile.app.chat.ChatMessage
import io.github.ts3mobile.app.chat.ChatMessageKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatRichBodyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun renderingAndCancellingDoNotOpenTheUrlAndConfirmOpensOnlyTheDisplayedDestination() {
        val opened = mutableListOf<String>()
        val target = "https://example.invalid/path"
        val handler =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened.add(uri)
                }
            }
        rule.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) {
                ChatMessageRow(message("[url=$target]A link[/url]"))
            }
        }
        val link = rule.onNodeWithText("A link")
        link.assertIsDisplayed()
        rule.runOnIdle { assertEquals(emptyList<String>(), opened) }
        link.performTouchInput { click(center) }
        rule.onNodeWithText(target).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(emptyList<String>(), opened) }
        link.performTouchInput { click(center) }
        rule.onNodeWithText("Open link").performClick()
        rule.runOnIdle { assertEquals(listOf(target), opened) }
    }

    @Test fun truncatedMessageDisplaysTheOriginalText() {
        val original = "[url=https://example.invalid]View[/url]"
        rule.setContent { ChatRichBody(original, truncated = true) }
        rule.onNodeWithText(original).assertIsDisplayed()
    }

    @Test fun copyUsesTheOriginalBbcodeRatherThanTheRenderedText() {
        val original = "[b]Original[/b]"
        var copied: AnnotatedString? = null
        val clipboard =
            object : ClipboardManager {
                override fun getText(): AnnotatedString? = copied

                override fun setText(annotatedString: AnnotatedString) {
                    copied = annotatedString
                }
            }
        rule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                ChatMessageRow(message(original))
            }
        }
        rule.onNodeWithText("Original").assertIsDisplayed()
        rule.onNodeWithTag("chat_bubble_1").performTouchInput { longClick() }
        rule.onNodeWithText("Copy message").performClick()
        rule.runOnIdle { assertEquals(original, copied?.text) }
    }

    private fun message(body: String): ChatMessage = ChatMessage(1, 1, 0, ChatMessageKind.INCOMING, 2, "Fixture", body)
}
