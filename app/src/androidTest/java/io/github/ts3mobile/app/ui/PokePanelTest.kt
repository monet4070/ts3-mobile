package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.protocol.TextTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PokePanelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionWindow() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun userMenuSendsDistinctPokeWithExactReasonAndKeepsTextDraft() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("channel draft")
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        openPoke()
        rule.onNodeWithTag("poke_confirm").assertIsEnabled()
        rule.onNodeWithTag("poke_reason").performClick().performTextInput(" 中文😀 | \\ ")
        rule.onNodeWithTag("poke_confirm").performClick()
        rule.onNodeWithText("Poke sent").assertIsDisplayed()
        rule.onNodeWithContentDescription("More actions for Sam").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf(TextTarget.Private(2, "fixture-2") to " 中文😀 | \\ "), fixture.sentPokes)
            assertTrue(fixture.sentBodies.isEmpty())
            assertEquals("channel draft", fixture.chat.value.conversations.first().draft)
        }
    }

    @Test fun cancelSendsNothingAndReasonLimitUsesUtf8Bytes() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openPoke()
        rule.onNodeWithTag("poke_reason").performTextInput("😀".repeat(26))
        rule.onNodeWithTag("poke_confirm").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertTrue(fixture.sentPokes.isEmpty()) }
        rule.onNodeWithContentDescription("More actions for Sam").performClick()
        rule.onNodeWithText("Poke").performClick()
        rule.onNodeWithTag("poke_confirm").assertIsEnabled().performClick()
        rule.onNodeWithText("Poke sent").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(TextTarget.Private(2, "fixture-2") to ""), fixture.sentPokes) }
    }

    @Test fun identityChangeWhileReasonDialogIsOpenDisablesSend() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openPoke()
        rule.onNodeWithTag("poke_reason").performTextInput("keep unsent reason")
        rule.runOnIdle {
            val participants =
                fixture.state.value.snapshot.participants.map {
                    if (it.id == 2) it.copy(uniqueIdentifier = "replacement") else it
                }
            fixture.state.value = fixture.state.value.copy(snapshot = fixture.state.value.snapshot.copy(participants = participants))
            fixture.chatStore.bind(1, 1, fixture.chat.value.binding, participants = participants)
            fixture.refreshChat()
        }
        rule.onNodeWithTag("poke_confirm").assertIsNotEnabled()
        rule.onNodeWithText("User offline or identity changed. Select the member again.").assertIsDisplayed()
        rule.onNodeWithText("keep unsent reason").assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.sentPokes.isEmpty()) }
    }

    @Test fun incomingPokeReleasesPttShowsNoticeAndLeavesPrivateHistoryAndUnread() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithContentDescription("Push to talk").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { fixture.receivePoke("Please check chat") }
        rule.onNodeWithText("Sam poked you").assertIsDisplayed()
        rule.onNodeWithText("Please check chat").assertIsDisplayed()
        rule.runOnIdle { assertEquals(false, fixture.transmission.last()) }
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertEquals(1, fixture.chat.value.totalUnread) }
        rule.onNodeWithText("Chat").performClick()
        val id = fixture.chat.value.conversations.single { it.key == ChatConversationKey.User("fixture-2") }.id
        rule.onNodeWithTag("chat_conversation_picker").performClick()
        rule.onNodeWithTag("chat_conversation_$id").performClick()
        rule.onNodeWithText("Poked you").assertIsDisplayed()
        rule.onNodeWithText("Please check chat").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, fixture.chat.value.totalUnread) }
    }

    @Test fun latestPokeReplacesVisibleNoticeWithoutLosingEitherHistoryEntry() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.runOnIdle { fixture.receivePoke("first notice") }
        rule.onNodeWithText("first notice").assertIsDisplayed()
        rule.runOnIdle { fixture.receivePoke("second notice") }
        rule.onNodeWithText("second notice").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle {
            assertEquals(listOf("first notice", "second notice"), fixture.chat.value.conversations.last().messages.map { it.body })
        }
    }

    @Test fun chineseLargeTextDialogKeepsReasonAndActionsReachableWithKeyboard() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh", fontScale = 1.8f) }
        rule.onNodeWithText("用户").performClick()
        rule.onNode(hasScrollAction() and hasAnyDescendant(hasText("Alex")))
            .performScrollToNode(hasContentDescription("用户操作：Sam"))
        rule.onNodeWithContentDescription("用户操作：Sam").performClick()
        rule.onNodeWithText("戳一下").performClick()
        rule.onNodeWithTag("poke_reason").performClick().performTextInput("提醒一下")
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("戳一下 Sam").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1.8f, layouts.single().layoutInput.density.fontScale, 0.01f)
        rule.onNodeWithTag("poke_confirm").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
        val output = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(output, "poke-zh-large-keyboard.png").outputStream().use {
            check(rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun openPoke() {
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithContentDescription("More actions for Sam").performClick()
        rule.onNodeWithText("Poke").performClick()
    }
}
